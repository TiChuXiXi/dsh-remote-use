/**
 * 极小的 HTTP 工具层：读体（带硬上限）、发 JSON、cookie 解析与序列化、客户端 IP。
 * 不引入任何框架 —— 本插件的所有路由直接拥有 node:http 的请求与响应。
 */
import { timingSafeEqual } from 'node:crypto'
import type { IncomingMessage, ServerResponse } from 'node:http'

/** 请求体超过配置上限。 */
export class PayloadTooLargeError extends Error {
  constructor(readonly limit: number) {
    super(`request body exceeds ${limit} bytes`)
    this.name = 'PayloadTooLargeError'
  }
}

/** 请求体不是合法 JSON。 */
export class BadJsonError extends Error {
  constructor(message = 'request body is not valid JSON') {
    super(message)
    this.name = 'BadJsonError'
  }
}

/**
 * 读完整个请求体，超过 limit 立即销毁连接并抛错。
 *
 * 边读边累加，绝不相信 Content-Length —— 它可被伪造。也不把 body 存成字符串
 * 直到确认长度，避免用一个超大 body 打爆内存。
 */
export function readBody(req: IncomingMessage, limit: number): Promise<Buffer> {
  return new Promise((resolve, reject) => {
    const chunks: Buffer[] = []
    let total = 0
    let settled = false

    const finish = (fn: () => void): void => {
      if (settled) return
      settled = true
      req.removeListener('data', onData)
      req.removeListener('end', onEnd)
      req.removeListener('error', onError)
      fn()
    }

    function onData(chunk: Buffer): void {
      total += chunk.length
      if (total > limit) {
        finish(() => {
          req.destroy()
          reject(new PayloadTooLargeError(limit))
        })
        return
      }
      chunks.push(chunk)
    }

    function onEnd(): void {
      finish(() => resolve(Buffer.concat(chunks)))
    }

    function onError(error: Error): void {
      finish(() => reject(error))
    }

    req.on('data', onData)
    req.on('end', onEnd)
    req.on('error', onError)
  })
}

/** 读体并解析 JSON 对象。 */
export async function readJsonObject(req: IncomingMessage, limit: number): Promise<Record<string, unknown>> {
  const body = await readBody(req, limit)
  let parsed: unknown
  try {
    parsed = JSON.parse(body.toString('utf8'))
  } catch {
    throw new BadJsonError()
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new BadJsonError('request body must be a JSON object')
  }
  return parsed as Record<string, unknown>
}

/** JSON 错误响应的统一形状。 */
export interface ErrorBody {
  ok: false
  error: string
  message?: string
  [extra: string]: unknown
}

/** 发送 JSON 响应。 */
export function sendJson(
  res: ServerResponse,
  status: number,
  body: unknown,
  headers: Record<string, string | string[]> = {},
): void {
  if (res.headersSent || res.writableEnded) return
  const payload = Buffer.from(JSON.stringify(body), 'utf8')
  res.writeHead(status, {
    'content-type': 'application/json; charset=utf-8',
    'content-length': String(payload.byteLength),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...headers,
  })
  res.end(payload)
}

/** 发送统一形状的 JSON 错误。 */
export function sendError(
  res: ServerResponse,
  status: number,
  error: string,
  message?: string,
  headers: Record<string, string> = {},
): void {
  const body: ErrorBody = { ok: false, error }
  if (message !== undefined) body.message = message
  sendJson(res, status, body, headers)
}

/** 发送一小段文本。 */
export function sendText(
  res: ServerResponse,
  status: number,
  text: string,
  contentType = 'text/plain; charset=utf-8',
  headers: Record<string, string> = {},
): void {
  if (res.headersSent || res.writableEnded) return
  const payload = Buffer.from(text, 'utf8')
  res.writeHead(status, {
    'content-type': contentType,
    'content-length': String(payload.byteLength),
    'cache-control': 'no-store',
    'x-content-type-options': 'nosniff',
    ...headers,
  })
  res.end(payload)
}

/** 解析 Cookie 头为名字到值的映射。 */
export function parseCookies(header: string | undefined): Map<string, string> {
  const out = new Map<string, string>()
  if (typeof header !== 'string' || header.length === 0) return out
  for (const segment of header.split(';')) {
    const at = segment.indexOf('=')
    if (at === -1) continue
    const name = segment.slice(0, at).trim()
    if (name.length === 0) continue
    out.set(name, segment.slice(at + 1).trim())
  }
  return out
}

/** 序列化一个 Set-Cookie 值。 */
export function serializeCookie(
  name: string,
  value: string,
  options: { maxAgeSeconds?: number; secure?: boolean; httpOnly?: boolean; sameSite?: 'Strict' | 'Lax' | 'None'; path?: string },
): string {
  const parts = [`${name}=${value}`]
  parts.push(`Path=${options.path ?? '/'}`)
  if (options.maxAgeSeconds !== undefined) parts.push(`Max-Age=${Math.max(0, Math.trunc(options.maxAgeSeconds))}`)
  if (options.httpOnly !== false) parts.push('HttpOnly')
  parts.push(`SameSite=${options.sameSite ?? 'Strict'}`)
  if (options.secure === true) parts.push('Secure')
  return parts.join('; ')
}

/**
 * 解析客户端 IP。
 *
 * 只信 X-Forwarded-For 的**最右侧**一项 —— 那是 Cloudflare 边缘**追加**的、
 * 最接近真实来源的一项。最左侧由客户端完全可控，信它等于把限速交给攻击者绕过。
 * 无 XFF 时回退到 socket 地址。
 */
export function clientIp(req: IncomingMessage, trustForwarded: boolean): string {
  if (trustForwarded) {
    const header = req.headers['x-forwarded-for']
    const raw = Array.isArray(header) ? header[header.length - 1] : header
    if (typeof raw === 'string' && raw.length > 0) {
      const parts = raw.split(',')
      const last = parts[parts.length - 1]
      if (last !== undefined) {
        const trimmed = last.trim()
        if (trimmed.length > 0) return normalizeIp(trimmed)
      }
    }
  }
  return normalizeIp(req.socket.remoteAddress ?? 'unknown')
}

/** 去掉 IPv6 映射前缀，让 127.0.0.1 与 ::ffff:127.0.0.1 归一到同一个限速桶。 */
function normalizeIp(value: string): string {
  if (value.startsWith('::ffff:')) return value.slice('::ffff:'.length)
  return value
}

/** 安全的常量时间字符串比较（长度不同直接返回 false）。 */
export function constantTimeEqual(a: string, b: string): boolean {
  const ab = Buffer.from(a, 'utf8')
  const bb = Buffer.from(b, 'utf8')
  if (ab.byteLength !== bb.byteLength) return false
  return timingSafeEqual(ab, bb)
}
