/**
 * 网关服务器：唯一的外部入口。
 *
 * 架构不变量（违反任何一条都等于把电脑控制权交出去）：
 *   1. 只监听回环地址（config 层已强制）。
 *   2. 认证检查在**所有**文件路由与代理路由之前执行，无一例外。
 *   3. 未配置密码时 fail-closed：除 /gateway/health 外一律 503。
 *   4. 转发给 dsh 的请求一律把 Host 改写为 `127.0.0.1:<dshPort>`，并只注入
 *      网关自己兑换到的 dsh 会话 cookie；客户端自带的 cookie 全部丢弃。
 *
 * 为什么不把路由注册到 dsh 自己的 webServer（那样更省事）：
 *   dsh 的 `/api` 前缀路由与 SPA fallback 由 dsh 自己注册，插件无法插到它们
 *   **前面**。若隧道指向 3080，`/api` 的流量就绕过了本插件的认证层，审计与限速
 *   也一并失效。独立监听 3081 才能真正做到「所有外部流量必须经过插件认证」。
 *   （`webServer.registerFallback()` 只有一个席位，已被 frontend-static 占用，
 *     第二次注册会直接抛错 —— 这一点也在源码里核实过。）
 */
import http from 'node:http'
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'
import type { GatewayConfig } from '../config.js'
import { clientIp, sendError, sendText } from '../http-util.js'
import type { AuditLog } from '../log.js'
import { errText, log, warn } from '../log.js'
import type { AuthRoutes } from '../auth/routes.js'
import type { TokenPayload } from '../auth/token.js'
import type { DshSessionCookie } from './dsh-cookie.js'
import { DshCookieError } from './dsh-cookie.js'

/** 逐跳头，绝不转发。 */
const HOP_BY_HOP = new Set([
  'connection',
  'keep-alive',
  'proxy-authenticate',
  'proxy-authorization',
  'te',
  'trailer',
  'transfer-encoding',
  'upgrade',
])

/**
 * 可为重放而缓冲的请求体上限。
 *
 * dsh 在 401 时（进程重启过）需要重新兑换 cookie 并重放请求。GET/HEAD 天然可重放；
 * 小的 JSON RPC 体（dsh 界面的全部 RPC 都在几十 KB 内）也缓冲下来以便重放。
 * 超过此阈值的体（文件上传流）直接流式转发，不重放 —— 重放一个几百 MB 的
 * 上传代价过大，且流已经消费掉了。
 */
const REPLAY_BODY_LIMIT = 1 * 1024 * 1024

/** 请求体读取计划。 */
type BodyPlan =
  | { mode: 'replay'; buffer: Buffer }
  | { mode: 'stream' }

/**
 * 决定请求体如何转发。
 *
 * 小体全部读进内存（可重放）；一旦超过上限，立刻把已读的字节 `unshift()` 回
 * 可读缓冲并停止消费 —— 这样调用方仍能用 `req.pipe(upstream)` 流式转发剩余部分。
 * 不这么做的话，`for await ... break` 会 destroy 掉流。
 */
function planBody(req: IncomingMessage): Promise<BodyPlan> {
  const method = (req.method ?? 'GET').toUpperCase()
  if (method === 'GET' || method === 'HEAD' || method === 'OPTIONS') {
    return Promise.resolve({ mode: 'replay', buffer: Buffer.alloc(0) })
  }

  return new Promise<BodyPlan>((resolve, reject) => {
    const chunks: Buffer[] = []
    let total = 0

    const cleanup = (): void => {
      req.removeListener('data', onData)
      req.removeListener('end', onEnd)
      req.removeListener('error', onError)
    }

    const onData = (chunk: Buffer): void => {
      chunks.push(chunk)
      total += chunk.length
      if (total <= REPLAY_BODY_LIMIT) return
      cleanup()
      req.pause()
      // 把已消费的字节还回去，交给 pipe 继续流式转发。
      req.unshift(Buffer.concat(chunks))
      resolve({ mode: 'stream' })
    }

    const onEnd = (): void => {
      cleanup()
      resolve({ mode: 'replay', buffer: Buffer.concat(chunks) })
    }

    const onError = (error: Error): void => {
      cleanup()
      reject(error)
    }

    req.on('data', onData)
    req.on('end', onEnd)
    req.on('error', onError)
  })
}

/** 文件服务处理器（由 files/routes.ts 实现）。 */
export interface FsHandler {
  handle(req: IncomingMessage, res: ServerResponse, url: URL, user: TokenPayload): Promise<boolean>
}

/** 网关服务器依赖。 */
export interface GatewayDeps {
  config: GatewayConfig
  /**
   * 转发目标端口 —— dsh 实际监听的端口。
   *
   * **必须显式传入，不能从 config.dshPort 读**：config.dshPort 为 0 表示
   * 「自动采用 ctx.webServer.port」，0 本身不是可用端口，Node 会把它当成默认的 80。
   */
  dshPort: number
  auth: AuthRoutes
  files: FsHandler
  cookie: DshSessionCookie
  audit: AuditLog
}

/** 网关服务器。 */
export class GatewayServer {
  private server: http.Server | null = null
  private listeningPort = 0

  constructor(private readonly deps: GatewayDeps) {}

  /** 实际监听端口。 */
  get port(): number {
    return this.listeningPort
  }

  private get config(): GatewayConfig {
    return this.deps.config
  }

  /** 转发目标：dsh 自己监听的端口。 */
  private get dshPort(): number {
    return this.deps.dshPort
  }

  /** 启动监听。 */
  async start(): Promise<number> {
    const server = http.createServer((req, res) => {
      void this.handleRequest(req, res)
    })

    // 长连接（SSE / WebSocket 之上的 dsh 流）不能被默认超时掐断。
    server.requestTimeout = 0
    server.headersTimeout = 60_000
    server.keepAliveTimeout = 72_000

    server.on('upgrade', (req, socket, head) => {
      this.handleUpgrade(req, socket as Duplex, head)
    })
    server.on('clientError', (error, socket) => {
      if (!socket.writable) return
      socket.end('HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n')
      warn(`客户端连接错误: ${errText(error)}`)
    })

    await new Promise<void>((resolve, reject) => {
      server.once('error', reject)
      server.listen(this.config.gatewayPort, this.config.gatewayHost, () => {
        server.removeListener('error', reject)
        resolve()
      })
    })

    const address = server.address()
    this.listeningPort = typeof address === 'object' && address !== null ? address.port : this.config.gatewayPort
    this.server = server
    return this.listeningPort
  }

  /** 关闭监听。 */
  async stop(): Promise<void> {
    const server = this.server
    if (server === null) return
    this.server = null
    await new Promise<void>((resolve) => {
      server.close(() => {
        resolve()
      })
      // 不等长连接自然结束，直接销毁。
      server.closeAllConnections()
    })
  }

  // ---------------------------------------------------------------- 请求分发

  private async handleRequest(req: IncomingMessage, res: ServerResponse): Promise<void> {
    let url: URL
    try {
      url = new URL(req.url ?? '/', `http://${req.headers['host'] ?? 'localhost'}`)
    } catch {
      sendError(res, 400, 'bad_request', 'URL 无法解析')
      return
    }

    try {
      // fail-closed：没有密码时不提供任何受保护能力。只放行健康检查与登录页 ——
      // 登录页本身会显示「尚未设置密码」并给出修复命令，是唯一的自救入口，
      // 它不暴露任何 dsh 能力。
      if (!this.deps.auth.isConfigured()) {
        if (url.pathname === '/gateway/health' || url.pathname === '/login') {
          await this.deps.auth.handle(req, res, url)
          return
        }
        sendError(
          res,
          503,
          'not_configured',
          '网关尚未设置密码，已拒绝全部请求。请运行 `npx dsh-remote-gateway set-password`。',
        )
        return
      }

      if (await this.deps.auth.handle(req, res, url)) return

      const outcome = this.deps.auth.authenticate(req)
      if (!outcome.ok) {
        this.rejectUnauthenticated(req, res, url, outcome.reason)
        return
      }

      if (url.pathname === '/fs' || url.pathname.startsWith('/fs/')) {
        await this.deps.files.handle(req, res, url, outcome.payload)
        return
      }

      await this.forward(req, res)
    } catch (error) {
      warn(`处理 ${req.method ?? '?'} ${url.pathname} 失败: ${errText(error)}`)
      if (!res.headersSent) sendError(res, 500, 'internal', '网关内部错误')
      else res.destroy()
    }
  }

  /** 未认证时的响应：页面请求跳登录页，其余一律 401 JSON。 */
  private rejectUnauthenticated(
    req: IncomingMessage,
    res: ServerResponse,
    url: URL,
    reason: 'missing' | 'invalid',
  ): void {
    this.deps.audit.record({
      action: 'auth.rejected',
      ip: clientIp(req, true),
      path: url.pathname,
      status: 401,
      detail: reason,
    })

    const wantsHtml = (req.headers['accept'] ?? '').includes('text/html')
    const isApiLike =
      url.pathname.startsWith('/api') ||
      url.pathname.startsWith('/fs') ||
      url.pathname.startsWith('/gateway') ||
      url.pathname.startsWith('/plugins')
    if (wantsHtml && !isApiLike && (req.method === 'GET' || req.method === 'HEAD')) {
      sendText(res, 302, '', 'text/plain; charset=utf-8', { location: '/login', 'cache-control': 'no-store' })
      return
    }
    sendError(res, 401, 'unauthorized', '需要先登录网关')
  }

  // ------------------------------------------------------------------- 反向代理

  /** 构造转发给 dsh 的请求头。 */
  private upstreamHeaders(req: IncomingMessage, cookie: string): http.OutgoingHttpHeaders {
    const headers: http.OutgoingHttpHeaders = {}

    for (const [name, value] of Object.entries(req.headers)) {
      if (value === undefined) continue
      const key = name.toLowerCase()
      if (HOP_BY_HOP.has(key)) continue
      // Host 必须改写；cookie 由网关独占；Origin/Referer 会让 dsh 判为跨源。
      if (key === 'host' || key === 'cookie' || key === 'origin' || key === 'referer') continue
      // 网关自己的 Bearer 凭据绝不外泄给 dsh。
      if (key === 'authorization') continue
      // 长度与编码由本层重新决定。
      if (key === 'content-length' || key === 'transfer-encoding') continue
      // 由本层重新生成。
      if (key === 'x-forwarded-for' || key === 'x-forwarded-host' || key === 'x-forwarded-proto') continue
      headers[key] = value
    }

    const clientHost = req.headers['host']
    const originalProto = req.headers['x-forwarded-proto']
    const proto = typeof originalProto === 'string' && originalProto.length > 0 ? originalProto : 'http'

    headers['host'] = this.deps.cookie.authority
    headers['cookie'] = cookie
    headers['x-forwarded-for'] = clientIp(req, true)
    headers['x-forwarded-proto'] = proto
    if (typeof clientHost === 'string') headers['x-forwarded-host'] = clientHost
    return headers
  }

  /**
   * 转发一个请求。
   *
   * 上游返回 401 时说明 dsh 重启过（launch token 与签名密钥都变了）：失效缓存、
   * 重新兑换一次、重放该请求一次（仅当请求体在缓冲阈值内或方法幂等）。
   */
  private async forward(req: IncomingMessage, res: ServerResponse): Promise<void> {
    let plan: BodyPlan
    try {
      plan = await planBody(req)
    } catch (error) {
      sendError(res, 400, 'bad_request', `读取请求体失败: ${errText(error)}`)
      return
    }

    const send = async (isRetry: boolean): Promise<void> => {
      let cookie: string
      try {
        cookie = await this.deps.cookie.cookie()
      } catch (error) {
        this.deps.audit.record({
          action: 'proxy.error',
          path: req.url ?? '',
          status: 502,
          detail: errText(error),
        })
        sendError(
          res,
          502,
          'upstream_error',
          error instanceof DshCookieError ? error.message : '无法取得 dsh 会话凭据',
        )
        return
      }

      const headers = this.upstreamHeaders(req, cookie)
      if (plan.mode === 'replay') headers['content-length'] = String(plan.buffer.byteLength)

      const upstream = http.request({
        host: '127.0.0.1',
        port: this.dshPort,
        method: req.method,
        path: req.url,
        headers,
      })

      upstream.on('response', (upRes) => {
        const status = upRes.statusCode ?? 502

        if (status === 401 && !isRetry) {
          upRes.resume()
          this.deps.cookie.invalidate()
          this.deps.audit.record({
            action: 'upstream.reauth',
            path: req.url ?? '',
            status: 401,
            detail: 'dsh 拒绝会话，已重新兑换并重放一次',
          })
          void send(true).catch((error: unknown) => {
            warn(`重放请求失败: ${errText(error)}`)
            if (!res.headersSent) sendError(res, 502, 'upstream_error', '重放请求失败')
          })
          return
        }

        const outHeaders: http.OutgoingHttpHeaders = {}
        for (const [name, value] of Object.entries(upRes.headers)) {
          if (value === undefined) continue
          const key = name.toLowerCase()
          if (HOP_BY_HOP.has(key)) continue
          // dsh 自己的会话 cookie 不下发：网关独占会话，且它的 authority 与
          // 公网域名不同，下发只会造成混淆。
          if (key === 'set-cookie') continue
          outHeaders[key] = value
        }
        res.writeHead(status, outHeaders)
        upRes.pipe(res)
      })

      upstream.on('error', (error) => {
        this.deps.audit.record({
          action: 'proxy.error',
          path: req.url ?? '',
          status: 502,
          detail: errText(error),
        })
        if (res.headersSent) {
          res.destroy()
          return
        }
        sendError(
          res,
          502,
          'upstream_error',
          `连接 dsh（127.0.0.1:${String(this.dshPort)}）失败: ${errText(error)}`,
        )
      })

      if (plan.mode === 'replay') upstream.end(plan.buffer)
      else req.pipe(upstream)
    }

    await send(false)
  }

  // --------------------------------------------------------------- WebSocket

  private handleUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): void {
    void this.runUpgrade(req, socket, head).catch((error: unknown) => {
      warn(`WebSocket 升级处理失败: ${errText(error)}`)
      this.failUpgrade(socket, 502, 'Bad Gateway')
    })
  }

  private failUpgrade(socket: Duplex, status: number, reason: string): void {
    if (!socket.writable) {
      socket.destroy()
      return
    }
    socket.write(
      `HTTP/1.1 ${String(status)} ${reason}\r\nConnection: close\r\nContent-Length: 0\r\n\r\n`,
    )
    socket.destroy()
  }

  private async runUpgrade(req: IncomingMessage, socket: Duplex, head: Buffer): Promise<void> {
    if (!this.deps.auth.isConfigured()) {
      this.failUpgrade(socket, 503, 'Service Unavailable')
      return
    }

    const outcome = this.deps.auth.authenticate(req)
    if (!outcome.ok) {
      this.deps.audit.record({
        action: 'auth.rejected',
        ip: clientIp(req, true),
        path: req.url ?? '',
        status: 401,
        detail: `upgrade:${outcome.reason}`,
      })
      this.failUpgrade(socket, 401, 'Unauthorized')
      return
    }

    let cookie: string
    try {
      cookie = await this.deps.cookie.cookie()
    } catch (error) {
      this.deps.audit.record({ action: 'proxy.error', path: req.url ?? '', status: 502, detail: errText(error) })
      this.failUpgrade(socket, 502, 'Bad Gateway')
      return
    }

    const headers = this.upstreamHeaders(req, cookie)
    // 升级握手头必须原样带到上游。
    headers['connection'] = 'Upgrade'
    headers['upgrade'] = typeof req.headers['upgrade'] === 'string' ? req.headers['upgrade'] : 'websocket'
    for (const name of ['sec-websocket-key', 'sec-websocket-version', 'sec-websocket-protocol', 'sec-websocket-extensions']) {
      const value = req.headers[name]
      if (typeof value === 'string' && value.length > 0) headers[name] = value
    }

    const upstream = http.request({
      host: '127.0.0.1',
      port: this.dshPort,
      method: 'GET',
      path: req.url,
      headers,
    })

    upstream.on('upgrade', (upRes, upSocket, upHead) => {
      let raw = `HTTP/1.1 ${String(upRes.statusCode ?? 101)} ${upRes.statusMessage ?? 'Switching Protocols'}\r\n`
      for (const [name, value] of Object.entries(upRes.headers)) {
        if (value === undefined) continue
        if (name.toLowerCase() === 'set-cookie') continue
        if (Array.isArray(value)) {
          for (const item of value) raw += `${name}: ${item}\r\n`
        } else {
          raw += `${name}: ${value}\r\n`
        }
      }
      raw += '\r\n'
      socket.write(raw)
      // 上游在响应头之后已经读到的字节要原样转给客户端；客户端 head 同理转上游。
      if (upHead.length > 0) socket.write(upHead)
      if (head.length > 0) upSocket.write(head)

      // Duplex 上是没有 setNoDelay 的（它是 net.Socket 的能力），这里做能力探测。
      const tune = (stream: Duplex): void => {
        if ('setNoDelay' in stream && typeof stream.setNoDelay === 'function') {
          stream.setNoDelay(true)
        }
      }
      tune(socket)
      tune(upSocket)
      socket.pipe(upSocket)
      upSocket.pipe(socket)

      const teardown = (): void => {
        socket.destroy()
        upSocket.destroy()
      }
      socket.on('error', teardown)
      upSocket.on('error', teardown)
      socket.on('close', teardown)
      upSocket.on('close', teardown)

      log(`WebSocket 已打通 ${req.url ?? ''}`)
    })

    // 上游拒绝升级（例如 403/404）：把状态与头原样告诉客户端，然后关闭。
    upstream.on('response', (upRes) => {
      let raw = `HTTP/1.1 ${String(upRes.statusCode ?? 502)} ${upRes.statusMessage ?? 'Bad Gateway'}\r\n`
      for (const [name, value] of Object.entries(upRes.headers)) {
        if (value === undefined) continue
        if (name.toLowerCase() === 'set-cookie') continue
        raw += `${name}: ${Array.isArray(value) ? value.join(', ') : String(value)}\r\n`
      }
      raw += 'connection: close\r\n\r\n'
      if (socket.writable) socket.write(raw)
      socket.destroy()
      upRes.resume()
    })

    upstream.on('error', (error) => {
      this.deps.audit.record({ action: 'proxy.error', path: req.url ?? '', status: 502, detail: errText(error) })
      this.failUpgrade(socket, 502, 'Bad Gateway')
    })

    upstream.end()
  }
}
