/**
 * 审计日志与结构化日志。
 *
 * 审计日志是**只追加**的 JSONL：一行一个事件，便于 grep 与后续导入。
 * 写入失败绝不抛出 —— 日志系统不能成为拒绝服务的入口。
 */
import { appendFileSync, mkdirSync } from 'node:fs'
import { join } from 'node:path'

/** 审计事件类型。 */
export type AuditAction =
  | 'login.success'
  | 'login.failure'
  | 'login.rate_limited'
  | 'logout'
  | 'revoke_all'
  | 'auth.rejected'
  | 'fs.list'
  | 'fs.download'
  | 'fs.upload'
  | 'fs.upload.chunk'
  | 'fs.denied'
  | 'proxy.error'
  | 'proxy.unreplayable_401'
  | 'upstream.reauth'
  | 'startup'

/** 一条审计记录。 */
export interface AuditEvent {
  action: AuditAction
  /** 客户端 IP。 */
  ip?: string
  /** 涉及的用户名。 */
  user?: string
  /** 目标路径（文件操作）。 */
  path?: string
  /** 字节数（文件操作）。 */
  bytes?: number
  /** HTTP 状态码。 */
  status?: number
  /** 失败原因或补充说明。 */
  detail?: string
}

/** 审计日志写入器。 */
export class AuditLog {
  private readonly file: string
  private readonly enabled: boolean
  private dirReady = false

  constructor(stateDir: string, enabled: boolean) {
    this.file = join(stateDir, 'audit.log')
    this.enabled = enabled
    if (enabled) {
      try {
        mkdirSync(stateDir, { recursive: true, mode: 0o700 })
        this.dirReady = true
      } catch (error) {
        console.warn(`[dsh-remote-gateway] 审计日志目录不可用: ${errText(error)}`)
      }
    }
  }

  /** 记录一条审计事件。永不抛出。 */
  record(event: AuditEvent): void {
    const line = JSON.stringify({ time: new Date().toISOString(), ...event })
    if (this.enabled && this.dirReady) {
      try {
        appendFileSync(this.file, `${line}\n`, { mode: 0o600 })
      } catch {
        /* 审计写失败不影响请求处理 */
      }
    }
  }

  /** 审计日志文件绝对路径（供日志提示）。 */
  get path(): string {
    return this.file
  }
}

/** 把任意抛出物转成可读文本。 */
export function errText(error: unknown): string {
  if (error instanceof Error) return error.message
  return String(error)
}

/** 带前缀的普通日志。 */
export function log(message: string): void {
  console.log(`[dsh-remote-gateway] ${message}`)
}

/** 带前缀的告警。 */
export function warn(message: string): void {
  console.warn(`[dsh-remote-gateway] ${message}`)
}
