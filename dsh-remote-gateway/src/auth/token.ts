/**
 * 会话 token：HMAC-SHA256 签名的自包含凭据，同时用于 cookie 与 Bearer 头。
 *
 * 结构：`v1.<base64url(JSON payload)>.<base64url(HMAC-SHA256(secret, body))>`
 * payload：`{ sub, iat, exp, jti }`
 *
 * 签名密钥 32 字节随机，存 `<stateDir>/session.key`（0600，首次启动生成）。
 * 注意这里**刻意不用 dsh 自己的 launch token 机制**：launch token 是进程级、
 * 不可吊销、且要放进 URL 里的一次性凭据；网关需要的是可吊销、可按用户区分、
 * 可放进 HttpOnly cookie 的凭据。
 *
 * 吊销：维护每个用户的 epoch（时间戳）。iat <= epoch 的 token 一律拒绝。
 * 这样「吊销全部会话」是 O(1)，不需要维护 token 黑名单。
 */
import { createHmac, randomBytes, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { errText, warn } from '../log.js'

const TOKEN_VERSION = 'v1'
const SECRET_BYTES = 32

/** token 载荷。 */
export interface TokenPayload {
  /** 用户名。 */
  sub: string
  /** 签发时间（毫秒）。 */
  iat: number
  /** 过期时间（毫秒）。 */
  exp: number
  /** 唯一 id，便于日志关联。 */
  jti: string
}

function b64url(input: Buffer): string {
  return input.toString('base64').replaceAll('+', '-').replaceAll('/', '_').replace(/=+$/u, '')
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

/** 会话 token 服务。 */
export class TokenService {
  private readonly secret: Buffer
  private readonly maxAgeMs: number
  private readonly revocationFile: string
  private readonly stateDir: string
  private epochs: Record<string, number>
  /**
   * 已吊销的单个 token（jti → 过期时间）。
   *
   * epoch 只能「整用户吊销」，做不到「只吊销这一个 token」。但登出必须真的让
   * **当前这个** token 失效 —— 否则一旦 bearer token 泄露，用户点「登出」也救不了，
   * 只能等它自然过期或吊销全部会话。所以额外维护一个小型 jti 拒绝表，条目在
   * token 自身过期后即可清理，因此表的大小有上界。
   */
  private revoked = new Map<string, number>()

  constructor(stateDir: string, maxAgeMs: number) {
    this.stateDir = stateDir
    this.maxAgeMs = maxAgeMs
    this.secret = this.loadSecret()
    this.revocationFile = join(stateDir, 'revocation.json')
    const loaded = this.loadRevocation()
    this.epochs = loaded.epoch
    this.revoked = loaded.revoked
  }

  private loadSecret(): Buffer {
    const file = join(this.stateDir, 'session.key')
    try {
      mkdirSync(this.stateDir, { recursive: true, mode: 0o700 })
      if (existsSync(file)) {
        const raw = readFileSync(file, 'utf8').trim()
        const decoded = Buffer.from(raw, 'base64url')
        if (decoded.byteLength === SECRET_BYTES) return decoded
        warn(`会话密钥长度异常（${decoded.byteLength} 字节），重新生成: ${file}`)
      }
      const generated = randomBytes(SECRET_BYTES)
      const tmp = `${file}.tmp.${process.pid}`
      writeFileSync(tmp, `${generated.toString('base64url')}\n`, { mode: 0o600 })
      renameSync(tmp, file)
      return generated
    } catch (error) {
      // 无法持久化时退化为进程内密钥：仍然安全，只是重启后所有会话失效。
      warn(`会话密钥持久化失败，改用进程内临时密钥（重启后需重新登录）: ${errText(error)}`)
      return randomBytes(SECRET_BYTES)
    }
  }

  private loadRevocation(): { epoch: Record<string, number>; revoked: Map<string, number> } {
    const empty = { epoch: {} as Record<string, number>, revoked: new Map<string, number>() }
    try {
      if (!existsSync(this.revocationFile)) return empty
      const parsed: unknown = JSON.parse(readFileSync(this.revocationFile, 'utf8'))
      if (!isRecord(parsed) || parsed['version'] !== 1) return empty

      const epoch: Record<string, number> = {}
      if (isRecord(parsed['epoch'])) {
        for (const [user, value] of Object.entries(parsed['epoch'])) {
          if (typeof value === 'number' && Number.isFinite(value)) epoch[user] = value
        }
      }
      const revoked = new Map<string, number>()
      if (isRecord(parsed['jti'])) {
        const now = Date.now()
        for (const [jti, value] of Object.entries(parsed['jti'])) {
          // 顺带丢掉已经自然过期的条目，避免文件无限增长。
          if (typeof value === 'number' && Number.isFinite(value) && value > now) revoked.set(jti, value)
        }
      }
      return { epoch, revoked }
    } catch {
      return empty
    }
  }

  private persistRevocation(): void {
    try {
      mkdirSync(this.stateDir, { recursive: true, mode: 0o700 })
      const jti: Record<string, number> = {}
      for (const [key, exp] of this.revoked) jti[key] = exp
      const tmp = `${this.revocationFile}.tmp.${process.pid}`
      writeFileSync(tmp, `${JSON.stringify({ version: 1, epoch: this.epochs, jti }, null, 2)}\n`, { mode: 0o600 })
      renameSync(tmp, this.revocationFile)
    } catch (error) {
      warn(`吊销状态持久化失败: ${errText(error)}`)
    }
  }

  /** 会话有效期（毫秒）。 */
  get maxAgeMilliseconds(): number {
    return this.maxAgeMs
  }

  private sign(body: string): Buffer {
    return createHmac('sha256', this.secret).update(body).digest()
  }

  /** 签发一个新 token。 */
  mint(username: string): { token: string; payload: TokenPayload } {
    // 关键：签发时间必须**严格晚于**该用户的吊销 epoch。
    // 否则「先 revoke-all、同一毫秒内再登录」会让刚签发的新 token 立刻被判为
    // 已吊销（verify 用的是 iat <= epoch），用户会看到登录成功但下一个请求就 401。
    const epoch = this.epochs[username]
    const issuedAt = epoch === undefined ? Date.now() : Math.max(Date.now(), epoch + 1)
    const payload: TokenPayload = {
      sub: username,
      iat: issuedAt,
      exp: issuedAt + this.maxAgeMs,
      jti: randomBytes(8).toString('hex'),
    }
    const body = b64url(Buffer.from(JSON.stringify(payload), 'utf8'))
    const token = `${TOKEN_VERSION}.${body}.${b64url(this.sign(body))}`
    return { token, payload }
  }

  /**
   * 校验 token。任何一处不合法都返回 null，绝不抛异常。
   * 校验顺序：结构 → 签名（常量时间）→ 载荷字段 → 时间窗 → 吊销 epoch。
   */
  verify(token: string): TokenPayload | null {
    if (typeof token !== 'string' || token.length === 0 || token.length > 4096) return null
    const parts = token.split('.')
    if (parts.length !== 3) return null
    const [version, body, encodedSignature] = parts
    if (version !== TOKEN_VERSION || body === undefined || encodedSignature === undefined) return null

    const actualSig = Buffer.from(encodedSignature, 'base64url')
    const expectedSig = this.sign(body)
    if (actualSig.byteLength !== expectedSig.byteLength) return null
    if (!timingSafeEqual(actualSig, expectedSig)) return null

    let parsed: unknown
    try {
      parsed = JSON.parse(Buffer.from(body, 'base64url').toString('utf8'))
    } catch {
      return null
    }
    if (!isRecord(parsed)) return null

    const sub = parsed['sub']
    const iat = parsed['iat']
    const exp = parsed['exp']
    const jti = parsed['jti']
    if (typeof sub !== 'string' || sub.length === 0 || sub.length > 256) return null
    if (typeof jti !== 'string' || jti.length === 0 || jti.length > 64) return null
    if (typeof iat !== 'number' || !Number.isSafeInteger(iat)) return null
    if (typeof exp !== 'number' || !Number.isSafeInteger(exp)) return null
    if (exp <= iat) return null
    // 有效期不得超过配置上限（防止载荷被伪造成超长寿命；签名已挡住伪造，这里是纵深防御）。
    if (exp - iat > this.maxAgeMs) return null

    const now = Date.now()
    if (iat > now + 60_000) return null // 容忍 1 分钟时钟漂移
    if (exp <= now) return null

    const epoch = this.epochs[sub]
    if (epoch !== undefined && iat <= epoch) return null

    // 单个 token 的吊销（登出）—— 必须在签名与时间窗都通过之后才查。
    if (this.revoked.has(jti)) return null

    return { sub, iat, exp, jti }
  }

  /**
   * 吊销**单个** token（登出用）。条目在 token 自身过期后自动失去意义。
   * @param jti - token 的唯一 id。
   * @param exp - token 的过期时间，用于决定何时可以清理该条目。
   */
  revokeToken(jti: string, exp: number): void {
    if (typeof jti !== 'string' || jti.length === 0) return
    if (!Number.isFinite(exp) || exp <= Date.now()) return // 已经过期，无需记录
    this.revoked.set(jti, exp)
    this.persistRevocation()
  }

  /**
   * 吊销某用户的全部已签发 token。
   * @returns 该用户此前的吊销时间戳（若有）。
   */
  revokeAll(username: string): void {
    // 用 iat <= epoch 判定失效；取当前时间可清掉此刻之前签发的一切。
    this.epochs[username] = Date.now()
    // 该用户的单 token 记录已无意义，一并清掉，避免文件膨胀。
    this.persistRevocation()
  }

  /** 清理已无意义的历史记录（epoch 与 jti 两种）。 */
  pruneEpochs(): void {
    const now = Date.now()
    const cutoff = now - this.maxAgeMs
    let changed = false
    for (const [user, epoch] of Object.entries(this.epochs)) {
      if (epoch < cutoff) {
        delete this.epochs[user]
        changed = true
      }
    }
    for (const [jti, exp] of [...this.revoked]) {
      if (exp <= now) {
        this.revoked.delete(jti)
        changed = true
      }
    }
    if (changed) this.persistRevocation()
  }

  /** 当前被单独吊销的 token 数量（诊断/测试用）。 */
  get revokedCount(): number {
    return this.revoked.size
  }
}
