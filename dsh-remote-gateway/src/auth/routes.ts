/**
 * 认证路由与认证判定：`/gateway/*` 与 `/login`。
 *
 * 认证判定的唯一入口是 {@link AuthRoutes.authenticate}，网关的**每一个**代理与
 * 文件路由都必须先过它。token 可用两种方式携带，两者落到同一个校验函数：
 *   - `Cookie: drg_session=<token>`（浏览器）
 *   - `Authorization: Bearer <token>`（Android / WebSocket 握手）
 * 刻意**不支持** `?token=` 查询参数 —— 查询串会进 Cloudflare 访问日志与
 * Referer，把一个长期凭据放进 URL 是明确的降级。
 */
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { GatewayConfig } from '../config.js'
import {
  BadJsonError,
  PayloadTooLargeError,
  clientIp,
  parseCookies,
  readJsonObject,
  sendError,
  sendJson,
  sendText,
  serializeCookie,
} from '../http-util.js'
import type { AuditLog } from '../log.js'
import { errText, log, warn } from '../log.js'
import type { PasswordStore } from './password.js'
import type { RateLimiter } from './ratelimit.js'
import type { TokenPayload, TokenService } from './token.js'

/** 会话 cookie 名。 */
export const SESSION_COOKIE = 'drg_session'

/** 登录请求体上限：没有任何合法登录请求会超过这个大小。 */
const LOGIN_BODY_LIMIT = 8 * 1024

/** 认证判定的结果。 */
export type AuthOutcome = { ok: true; payload: TokenPayload } | { ok: false; reason: 'missing' | 'invalid' }

/** 认证路由依赖。 */
export interface AuthDeps {
  config: GatewayConfig
  passwords: PasswordStore
  tokens: TokenService
  limiter: RateLimiter
  audit: AuditLog
}

/** 登录页 HTML（内联资源，无外部请求，可在隧道下离线工作）。 */
function loginPage(configured: boolean): string {
  const notice = configured
    ? ''
    : '<p class="err">服务端尚未设置密码。请先运行 <code>npx dsh-remote-gateway set-password</code>。</p>'
  return `<!doctype html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="robots" content="noindex,nofollow">
<title>dsh 远程网关 · 登录</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; }
  body { margin:0; min-height:100dvh; display:flex; align-items:center; justify-content:center;
         background:#0b0d12; color:#e6e8ee; font:16px/1.5 system-ui,-apple-system,"Segoe UI",sans-serif; padding:24px; }
  form { width:100%; max-width:360px; background:#141821; border:1px solid #232a38; border-radius:14px; padding:24px; }
  h1 { margin:0 0 4px; font-size:19px; }
  p.sub { margin:0 0 20px; color:#8b93a7; font-size:13px; }
  label { display:block; margin:14px 0 6px; font-size:13px; color:#aab2c5; }
  input { width:100%; padding:11px 12px; border-radius:9px; border:1px solid #2b3446;
          background:#0f131b; color:#e6e8ee; font-size:16px; }
  input:focus { outline:2px solid #4d6bfe; outline-offset:1px; border-color:#4d6bfe; }
  button { width:100%; margin-top:22px; padding:12px; border:0; border-radius:9px;
           background:#4d6bfe; color:#fff; font-size:16px; font-weight:600; cursor:pointer; }
  button:disabled { opacity:.55; cursor:progress; }
  .err { margin:16px 0 0; padding:10px 12px; border-radius:8px; background:#3a1720;
         border:1px solid #6d2334; color:#ffb3c1; font-size:13px; }
  .err:empty { display:none; }
  code { background:#0f131b; padding:1px 5px; border-radius:4px; font-size:12px; }
</style>
</head>
<body>
<form id="f" autocomplete="on">
  <h1>dsh 远程网关</h1>
  <p class="sub">登录后可访问家用电脑上的 dsh。</p>
  ${notice}
  <label for="u">用户名</label>
  <input id="u" name="username" autocomplete="username" autocapitalize="none" autocorrect="off" required>
  <label for="p">密码</label>
  <input id="p" name="password" type="password" autocomplete="current-password" required>
  <button id="b" type="submit">登录</button>
  <p class="err" id="e"></p>
</form>
<script>
(function () {
  var f = document.getElementById('f'), e = document.getElementById('e'), b = document.getElementById('b');
  f.addEventListener('submit', async function (ev) {
    ev.preventDefault();
    e.textContent = ''; b.disabled = true;
    try {
      var r = await fetch('/gateway/login', {
        method: 'POST',
        headers: { 'content-type': 'application/json' },
        body: JSON.stringify({ username: f.username.value, password: f.password.value })
      });
      var d = await r.json().catch(function () { return {}; });
      if (r.ok && d.ok) { location.replace('/'); return; }
      e.textContent = r.status === 429 ? '尝试过于频繁，请稍后再试。'
        : r.status === 503 ? '服务端尚未配置密码。'
        : '用户名或密码错误。';
    } catch (x) {
      e.textContent = '网络错误：' + (x && x.message ? x.message : x);
    }
    b.disabled = false;
  });
})();
</script>
</body>
</html>
`
}

/** 认证路由。 */
export class AuthRoutes {
  constructor(private readonly deps: AuthDeps) {}

  private get config(): GatewayConfig {
    return this.deps.config
  }

  /** 是否已配置至少一个用户（或提供了明文密码）。 */
  isConfigured(): boolean {
    return this.config.adminPassword.length > 0 || !this.deps.passwords.isEmpty
  }

  /**
   * 从请求中提取并校验凭据。这是全网关唯一的认证判定点。
   * 不区分「没带凭据」与「凭据非法」的对外表现，但内部区分以便审计。
   */
  authenticate(req: IncomingMessage): AuthOutcome {
    const token = this.extractToken(req)
    if (token === null) return { ok: false, reason: 'missing' }
    const payload = this.deps.tokens.verify(token)
    if (payload === null) return { ok: false, reason: 'invalid' }
    return { ok: true, payload }
  }

  private extractToken(req: IncomingMessage): string | null {
    const auth = req.headers['authorization']
    if (typeof auth === 'string' && auth.length > 0) {
      const match = /^Bearer[ \t]+(.+)$/i.exec(auth.trim())
      if (match !== null && match[1] !== undefined) {
        const value = match[1].trim()
        if (value.length > 0) return value
      }
    }
    const cookies = parseCookies(req.headers['cookie'])
    const fromCookie = cookies.get(SESSION_COOKIE)
    if (fromCookie !== undefined && fromCookie.length > 0) return fromCookie
    return null
  }

  /** 写入会话 cookie；登录与续期共用。 */
  private setSessionCookie(res: ServerResponse, token: string): void {
    const maxAgeSeconds = Math.floor(this.deps.tokens.maxAgeMilliseconds / 1000)
    res.setHeader(
      'set-cookie',
      serializeCookie(SESSION_COOKIE, token, {
        maxAgeSeconds,
        secure: this.config.cookieSecure,
        httpOnly: true,
        sameSite: 'Strict',
        path: '/',
      }),
    )
  }

  private clearSessionCookie(res: ServerResponse): void {
    res.setHeader(
      'set-cookie',
      serializeCookie(SESSION_COOKIE, '', {
        maxAgeSeconds: 0,
        secure: this.config.cookieSecure,
        httpOnly: true,
        sameSite: 'Strict',
        path: '/',
      }),
    )
  }

  /**
   * 处理 `/gateway/*` 与 `/login`。
   * @returns 是否已处理（false 表示调用方继续走代理）。
   */
  async handle(req: IncomingMessage, res: ServerResponse, url: URL): Promise<boolean> {
    switch (url.pathname) {
      case '/gateway/health':
        this.handleHealth(req, res)
        return true
      case '/gateway/login':
        await this.handleLogin(req, res)
        return true
      case '/gateway/logout':
        this.handleLogout(req, res)
        return true
      case '/gateway/session':
        this.handleSession(req, res)
        return true
      case '/gateway/revoke-all':
        this.handleRevokeAll(req, res)
        return true
      case '/login':
        this.handleLoginPage(req, res)
        return true
      default:
        if (url.pathname.startsWith('/gateway/')) {
          sendError(res, 404, 'not_found')
          return true
        }
        return false
    }
  }

  private handleHealth(req: IncomingMessage, res: ServerResponse): void {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    sendJson(res, 200, {
      ok: true,
      service: 'dsh-remote-gateway',
      version: '1.0.0',
      configured: this.isConfigured(),
      uptimeMs: Math.round(process.uptime() * 1000),
    })
  }

  private handleLoginPage(req: IncomingMessage, res: ServerResponse): void {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    sendText(res, 200, loginPage(this.isConfigured()), 'text/html; charset=utf-8')
  }

  private handleLogout(req: IncomingMessage, res: ServerResponse): void {
    if (req.method !== 'POST' && req.method !== 'GET') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const outcome = this.authenticate(req)
    this.clearSessionCookie(res)
    // 光清 cookie 是不够的：Bearer token 是无状态的，客户端手里那一份仍然有效。
    // 登出必须真的把这个 token 吊销掉，否则 token 泄露后「登出」形同虚设。
    if (outcome.ok) this.deps.tokens.revokeToken(outcome.payload.jti, outcome.payload.exp)
    this.deps.audit.record({
      action: 'logout',
      ip: this.ip(req),
      ...(outcome.ok ? { user: outcome.payload.sub } : {}),
      status: 200,
    })
    sendJson(res, 200, { ok: true, revoked: outcome.ok })
  }

  private handleSession(req: IncomingMessage, res: ServerResponse): void {
    if (req.method !== 'GET' && req.method !== 'HEAD') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const outcome = this.authenticate(req)
    if (!outcome.ok) {
      sendError(res, 401, 'unauthorized')
      return
    }
    sendJson(res, 200, {
      ok: true,
      username: outcome.payload.sub,
      expiresAt: outcome.payload.exp,
    })
  }

  private handleRevokeAll(req: IncomingMessage, res: ServerResponse): void {
    if (req.method !== 'POST') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    const outcome = this.authenticate(req)
    if (!outcome.ok) {
      sendError(res, 401, 'unauthorized')
      return
    }
    this.deps.tokens.revokeAll(outcome.payload.sub)
    this.clearSessionCookie(res)
    this.deps.audit.record({
      action: 'revoke_all',
      ip: this.ip(req),
      user: outcome.payload.sub,
      status: 200,
    })
    log(`已吊销用户 ${outcome.payload.sub} 的全部会话`)
    sendJson(res, 200, { ok: true, revoked: 'all' })
  }

  private async handleLogin(req: IncomingMessage, res: ServerResponse): Promise<void> {
    if (req.method !== 'POST') {
      sendError(res, 405, 'method_not_allowed')
      return
    }
    // 未配置密码：拒绝一切，而不是悄悄放行（fail-closed）。
    if (!this.isConfigured()) {
      this.deps.audit.record({ action: 'login.failure', ip: this.ip(req), status: 503, detail: 'not_configured' })
      sendError(res, 503, 'not_configured', '服务端尚未设置密码')
      return
    }

    const ip = this.ip(req)
    const retryAfterMs = this.deps.limiter.retryAfterMs(ip)
    if (retryAfterMs > 0) {
      const retryAfterSeconds = Math.ceil(retryAfterMs / 1000)
      this.deps.audit.record({
        action: 'login.rate_limited',
        ip,
        status: 429,
        detail: `retryAfterMs=${retryAfterMs}`,
      })
      sendError(res, 429, 'rate_limited', '登录尝试过于频繁，请稍后再试', {
        'retry-after': String(retryAfterSeconds),
      })
      return
    }

    let body: Record<string, unknown>
    try {
      body = await readJsonObject(req, LOGIN_BODY_LIMIT)
    } catch (error) {
      if (error instanceof PayloadTooLargeError) {
        sendError(res, 413, 'payload_too_large')
        return
      }
      if (error instanceof BadJsonError) {
        sendError(res, 400, 'bad_request', error.message)
        return
      }
      sendError(res, 400, 'bad_request', errText(error))
      return
    }

    const usernameRaw = body['username']
    const passwordRaw = body['password']
    if (typeof usernameRaw !== 'string' || typeof passwordRaw !== 'string') {
      sendError(res, 400, 'bad_request', 'username 与 password 必须是字符串')
      return
    }
    const username = usernameRaw.trim()
    if (username.length === 0 || username.length > 256) {
      sendError(res, 400, 'bad_request', 'username 长度非法')
      return
    }

    let ok = this.deps.passwords.verify(username, passwordRaw)

    // 首次引导：config/env 提供了明文密码且该用户尚不存在时，落库为 scrypt 哈希。
    if (!ok && this.config.adminPassword.length > 0 && !this.deps.passwords.has(username)) {
      if (username === this.bootstrapUsername() && passwordRaw === this.config.adminPassword) {
        try {
          this.deps.passwords.setPassword(username, passwordRaw)
          ok = true
          log(`已把引导密码落库为 scrypt 哈希（用户 ${username}）`)
        } catch (error) {
          warn(`引导密码落库失败: ${errText(error)}`)
        }
      }
    }

    if (!ok) {
      this.deps.limiter.recordFailure(ip)
      const remaining = this.config.loginMaxAttempts - this.failureCount(ip)
      this.deps.audit.record({
        action: 'login.failure',
        ip,
        user: username,
        status: 401,
        detail: remaining > 0 ? `剩余尝试 ${remaining}` : '已达上限',
      })
      // 用户不存在与密码错误返回完全相同的响应体。
      sendError(res, 401, 'invalid_credentials')
      return
    }

    this.deps.limiter.reset(ip)
    const { token, payload } = this.deps.tokens.mint(username)
    this.setSessionCookie(res, token)
    this.deps.audit.record({ action: 'login.success', ip, user: username, status: 200 })
    sendJson(res, 200, { ok: true, token, username, expiresAt: payload.exp })
  }

  /** 引导密码对应的用户名：优先取已存在的第一个用户，否则 admin。 */
  private bootstrapUsername(): string {
    const existing = this.deps.passwords.list()
    return existing.length > 0 ? (existing[0] as string) : 'admin'
  }

  /** 估算某 IP 在当前窗口内的失败次数（仅用于日志展示）。 */
  private failureCount(ip: string): number {
    const retry = this.deps.limiter.retryAfterMs(ip)
    return retry > 0 ? this.config.loginMaxAttempts : 0
  }

  private ip(req: IncomingMessage): string {
    return clientIp(req, true)
  }
}
