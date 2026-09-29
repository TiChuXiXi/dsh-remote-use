/**
 * dsh 会话 cookie 的获取与缓存。
 *
 * 背景（对照 dsh 0.1.5-rc.3 源码核实）：
 *   dsh 的 `/api` 前缀路由有两道门 —— `isTrustedApiRequest()` 的 Host/Origin 信任
 *   栅栏（失败 403），然后是 `BrowserAuth.isAuthenticated()`（失败 401）。后者只认
 *   **authority 绑定**的 HMAC cookie：
 *     cookie 名 = 'dsh-auth-' + base64url(sha256(authority))
 *     签名载荷里的 authority 必须 === 从 Host 头规范化出来的 authority
 *   因此网关必须用**一个固定 authority** 去面对 dsh，并把 cookie 按同一 authority
 *   兑换出来。这里固定用 `127.0.0.1:<dshPort>` —— 它天然属于回环，永远通过信任栅栏，
 *   不需要给 dsh 配置 trustedHosts。
 *
 * 拿 launch token 的途径：本插件与 dsh web 同进程，`ctx.connection.authenticatedUrl()`
 * 是 dsh 暴露在 `HostConnectionHandle` 上的公开方法（dsh-web-app 自己也用它打印那行
 * `dsh web: http://127.0.0.1:3080/?token=...`）。因此**不需要**去解析 stdout ——
 * 那种做法既脆弱又多一个失败面。
 *
 * 兑换：`GET /?token=<launchToken>` + `Host: 127.0.0.1:<dshPort>` → 303 + Set-Cookie。
 * 不跟随重定向，直接抓 set-cookie。
 */
import http from 'node:http'
import { errText } from '../log.js'

/** 兑换超时。 */
const EXCHANGE_TIMEOUT_MS = 10_000

/** dsh 侧会话 cookie 的前缀。 */
const DSH_COOKIE_PREFIX = 'dsh-auth-'

/** 兑换失败。 */
export class DshCookieError extends Error {
  constructor(message: string) {
    super(message)
    this.name = 'DshCookieError'
  }
}

/** dsh 会话 cookie 提供者（单飞 + 缓存 + 可失效）。 */
export class DshSessionCookie {
  private cached: string | null = null
  private inflight: Promise<string> | null = null
  private exchangeCount = 0
  private obtainedAt = 0

  constructor(
    private readonly port: number,
    private readonly resolveTokenUrl: (baseUrl: string) => string,
    private readonly onEvent?: (kind: 'obtained' | 'invalidated' | 'error', detail: string) => void,
  ) {}

  /** 面对 dsh 时固定使用的 authority。 */
  get authority(): string {
    return `127.0.0.1:${String(this.port)}`
  }

  /** 已成功兑换的次数（诊断用）。 */
  get exchanges(): number {
    return this.exchangeCount
  }

  /** 主动失效缓存：dsh 进程重启后 launch token 与签名密钥都变了。 */
  invalidate(): void {
    this.cached = null
    this.obtainedAt = 0
    this.onEvent?.('invalidated', 'dsh 会话 cookie 已失效，下次请求将重新兑换')
  }

  /**
   * 确保 cookie 的「年龄」不超过 maxAgeMs，否则先重新兑换。
   *
   * 为什么需要它：重放要求请求体还能再发一遍，而**流式转发的大请求体已经消费掉了**，
   * 无法重放。所以对大请求，唯一能做的就是别让它带着一个陈旧 cookie 上路 ——
   * dsh 重启后 cookie 会失效，而大请求一旦因此吃 401，就只能失败重传。
   * 小请求不需要这个：它们可重放，401 时自愈即可，省掉这次额外往返。
   */
  async ensureFresh(maxAgeMs: number): Promise<string> {
    if (this.cached !== null && Date.now() - this.obtainedAt <= maxAgeMs) return this.cached
    if (this.cached !== null) {
      this.onEvent?.('invalidated', `会话 cookie 已存放超过 ${String(maxAgeMs)}ms，大请求前主动换新`)
      this.cached = null
      this.obtainedAt = 0
    }
    return this.cookie()
  }

  /**
   * 取得可直接放进 Cookie 头的 `name=value`。
   * 并发调用共享同一次兑换（单飞），避免启动瞬间打出多次兑换。
   */
  async cookie(): Promise<string> {
    if (this.cached !== null) return this.cached
    if (this.inflight !== null) return this.inflight

    const attempt = this.exchange()
      .then((value) => {
        this.cached = value
        this.obtainedAt = Date.now()
        this.exchangeCount += 1
        this.onEvent?.('obtained', `已兑换 dsh 会话 cookie（第 ${String(this.exchangeCount)} 次）`)
        return value
      })
      .catch((error: unknown) => {
        this.onEvent?.('error', `兑换 dsh 会话 cookie 失败: ${errText(error)}`)
        throw error
      })
      .finally(() => {
        this.inflight = null
      })

    this.inflight = attempt
    return attempt
  }

  private exchange(): Promise<string> {
    const authority = this.authority
    const baseUrl = `http://${authority}/`

    let tokenUrl: URL
    try {
      tokenUrl = new URL(this.resolveTokenUrl(baseUrl))
    } catch (error) {
      return Promise.reject(new DshCookieError(`authenticatedUrl() 返回的 URL 无法解析: ${errText(error)}`))
    }

    const token = tokenUrl.searchParams.get('token')
    if (token === null || token.length === 0) {
      return Promise.reject(new DshCookieError('ctx.connection.authenticatedUrl() 未返回 launch token'))
    }

    return new Promise<string>((resolve, reject) => {
      const req = http.request(
        {
          host: '127.0.0.1',
          port: this.port,
          method: 'GET',
          path: `/?token=${encodeURIComponent(token)}`,
          headers: {
            host: authority,
            'cache-control': 'no-store',
            accept: 'text/html',
            connection: 'close',
          },
        },
        (res) => {
          const status = res.statusCode ?? 0
          const raw = res.headers['set-cookie']
          // 关键：不跟随重定向，读完即弃。
          res.resume()

          const list = Array.isArray(raw) ? raw : raw === undefined ? [] : [raw]
          const match = list.find((entry) => entry.startsWith(DSH_COOKIE_PREFIX))
          if (match === undefined) {
            reject(
              new DshCookieError(
                `dsh 未返回 ${DSH_COOKIE_PREFIX}* cookie（HTTP ${String(status)}）。` +
                  `若 dsh 刚重启过，下一次请求会自动重试。`,
              ),
            )
            return
          }
          const separator = match.indexOf(';')
          const pair = separator === -1 ? match : match.slice(0, separator)
          resolve(pair.trim())
        },
      )

      req.setTimeout(EXCHANGE_TIMEOUT_MS, () => {
        req.destroy(new DshCookieError(`兑换 dsh 会话 cookie 超时（${String(EXCHANGE_TIMEOUT_MS)}ms）`))
      })
      req.on('error', (error) => {
        reject(
          error instanceof DshCookieError
            ? error
            : new DshCookieError(`连接 dsh 127.0.0.1:${String(this.port)} 失败: ${errText(error)}`),
        )
      })
      req.end()
    })
  }
}
