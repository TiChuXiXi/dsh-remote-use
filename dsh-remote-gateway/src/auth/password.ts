/**
 * 用户凭据存储：scrypt 哈希，绝不保存明文。
 *
 * 存储位置 `<stateDir>/users.json`，权限 0600。记录里保存每个用户自己的
 * scrypt 参数（N/r/p/keylen），这样将来调高成本参数时旧记录仍可校验。
 *
 * 防用户名枚举：用户不存在时也执行一次**等价成本**的 scrypt 计算，再返回失败。
 * 否则「用户不存在」会明显快于「密码错误」，攻击者据此可枚举用户名。
 */
import { randomBytes, scryptSync, timingSafeEqual } from 'node:crypto'
import { existsSync, mkdirSync, readFileSync, renameSync, statSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { errText, warn } from '../log.js'

/** scrypt 成本参数。N=2^14 时单次约 16 MiB / 数十毫秒，适合交互式登录。 */
const SCRYPT = { N: 16384, r: 8, p: 1, keylen: 64, maxmem: 128 * 1024 * 1024 } as const

/** 密码最小长度。 */
export const MIN_PASSWORD_LENGTH = 8

interface UserRecord {
  salt: string
  hash: string
  N: number
  r: number
  p: number
  keylen: number
  createdAt: number
  updatedAt: number
}

interface UserFile {
  version: 1
  users: Record<string, UserRecord>
}

/** 校验密码策略，返回错误说明或 null。 */
export function validatePasswordPolicy(password: string): string | null {
  if (typeof password !== 'string' || password.length < MIN_PASSWORD_LENGTH) {
    return `密码至少需要 ${MIN_PASSWORD_LENGTH} 位`
  }
  if (password.length > 1024) return '密码过长（上限 1024 字符）'
  return null
}

function derive(password: string, salt: Buffer, params: { N: number; r: number; p: number; keylen: number }): Buffer {
  return scryptSync(password, salt, params.keylen, {
    N: params.N,
    r: params.r,
    p: params.p,
    maxmem: SCRYPT.maxmem,
  })
}

/** scrypt 用户库。 */
export class PasswordStore {
  private readonly file: string
  private readonly dir: string
  private data: UserFile
  /** 用于「用户不存在」路径的假记录，保证失败路径耗时一致。 */
  private readonly dummy: UserRecord
  /** 上次读到文件时的 mtime；用于感知 CLI 在进程外改了用户库。 */
  private loadedMtimeMs = -1

  constructor(stateDir: string) {
    this.dir = stateDir
    this.file = join(stateDir, 'users.json')
    this.data = this.load()
    this.loadedMtimeMs = this.currentMtime()
    this.dummy = {
      salt: Buffer.alloc(16, 7).toString('base64url'),
      hash: Buffer.alloc(SCRYPT.keylen, 9).toString('base64url'),
      N: SCRYPT.N,
      r: SCRYPT.r,
      p: SCRYPT.p,
      keylen: SCRYPT.keylen,
      createdAt: 0,
      updatedAt: 0,
    }
  }

  private currentMtime(): number {
    try {
      return statSync(this.file).mtimeMs
    } catch {
      return 0
    }
  }

  /**
   * 若用户库文件在进程外被改动（例如刚跑完 `set-password`），重新加载。
   * 这样 CLI 设完密码后**不需要重启 dsh** 就能登录，避免一个很反直觉的坑。
   */
  private refreshIfChanged(): void {
    const mtime = this.currentMtime()
    if (mtime === this.loadedMtimeMs) return
    this.loadedMtimeMs = mtime
    this.data = this.load()
  }

  private load(): UserFile {
    try {
      if (!existsSync(this.file)) return { version: 1, users: {} }
      const parsed: unknown = JSON.parse(readFileSync(this.file, 'utf8'))
      if (
        typeof parsed === 'object' &&
        parsed !== null &&
        (parsed as { version?: unknown }).version === 1 &&
        typeof (parsed as { users?: unknown }).users === 'object' &&
        (parsed as { users?: unknown }).users !== null
      ) {
        return parsed as UserFile
      }
      warn(`用户库格式不受支持，按空处理: ${this.file}`)
      return { version: 1, users: {} }
    } catch (error) {
      warn(`用户库读取失败，按空处理: ${errText(error)}`)
      return { version: 1, users: {} }
    }
  }

  private persist(): void {
    mkdirSync(this.dir, { recursive: true, mode: 0o700 })
    const tmp = `${this.file}.tmp.${process.pid}.${randomBytes(4).toString('hex')}`
    writeFileSync(tmp, `${JSON.stringify(this.data, null, 2)}\n`, { mode: 0o600 })
    renameSync(tmp, this.file)
    // 记下自己写入后的 mtime，避免下一次 refresh 把自己刚写的内容当成外部改动。
    this.loadedMtimeMs = this.currentMtime()
  }

  /** 是否已有任何用户。 */
  get isEmpty(): boolean {
    this.refreshIfChanged()
    return Object.keys(this.data.users).length === 0
  }

  /** 是否存在该用户。 */
  has(username: string): boolean {
    this.refreshIfChanged()
    return Object.prototype.hasOwnProperty.call(this.data.users, username)
  }

  /** 所有用户名（不含哈希）。 */
  list(): string[] {
    this.refreshIfChanged()
    return Object.keys(this.data.users).sort()
  }

  /**
   * 设置或覆盖一个用户的密码。
   * @throws 密码不符合策略时抛出。
   */
  setPassword(username: string, password: string): void {
    const policyError = validatePasswordPolicy(password)
    if (policyError !== null) throw new Error(policyError)
    if (username.trim().length === 0) throw new Error('用户名不能为空')

    const salt = randomBytes(16)
    const record: UserRecord = {
      salt: salt.toString('base64url'),
      hash: derive(password, salt, SCRYPT).toString('base64url'),
      N: SCRYPT.N,
      r: SCRYPT.r,
      p: SCRYPT.p,
      keylen: SCRYPT.keylen,
      createdAt: this.data.users[username]?.createdAt ?? Date.now(),
      updatedAt: Date.now(),
    }
    this.data.users[username] = record
    this.persist()
  }

  /** 删除一个用户，返回是否真的删除了。 */
  remove(username: string): boolean {
    if (!this.has(username)) return false
    delete this.data.users[username]
    this.persist()
    return true
  }

  /**
   * 校验用户名 + 密码。用户不存在或密码错误都返回 false，且耗时一致。
   */
  verify(username: string, password: string): boolean {
    this.refreshIfChanged()
    const record = this.has(username) ? this.data.users[username] : undefined
    const effective = record ?? this.dummy

    let computed: Buffer
    try {
      computed = derive(password, Buffer.from(effective.salt, 'base64url'), {
        N: effective.N,
        r: effective.r,
        p: effective.p,
        keylen: effective.keylen,
      })
    } catch {
      return false
    }

    const expected = Buffer.from(effective.hash, 'base64url')
    const matches = computed.byteLength === expected.byteLength && timingSafeEqual(computed, expected)
    // 用户不存在时同样走到这里，但结果强制为 false。
    return record !== undefined && matches
  }
}
