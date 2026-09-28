/**
 * 插件配置：纯 JS 校验，**不导出 Schemastery Config** —— 本插件刻意零运行时依赖。
 * 这与 dsh-git-vcs 的做法一致（其源码注释同样写明「本插件刻意零运行时依赖」）。
 *
 * 为什么不用 Schemastery：`import z from '@deepseek-ai/schemastery'` 会让插件在
 * profile 目录里产生一个运行时解析依赖；对一个认证网关来说，少一个依赖就少一份
 * 供应链与解析失败风险。配置只有十几个键，手写校验完全可控，且能做范围钳制。
 */
import { homedir } from 'node:os'
import { join } from 'node:path'

/** 归一化后的插件配置。 */
export interface GatewayConfig {
  /** 网关绑定地址。刻意只允许回环地址。 */
  gatewayHost: string
  /** 网关监听端口；隧道 ingress 指向这里。 */
  gatewayPort: number
  /** dsh 自身监听端口；0 表示自动采用 ctx.webServer.port。 */
  dshPort: number
  /** 公网域名[:端口]，仅用于日志与展示。 */
  publicAuthority: string
  /** 工作区根目录；空表示用 dsh 进程的 cwd。 */
  workspaceRoot: string
  /** 明文管理员密码，仅用于首次引导。 */
  adminPassword: string
  /** 会话 token 有效期（天）。 */
  sessionMaxAgeDays: number
  /** 会话 cookie 是否带 Secure。Cloudflare 终结 TLS 时必须为 true。 */
  cookieSecure: boolean
  /** 单个请求体上限（字节）。 */
  maxUploadBytes: number
  /** 登录限速：窗口内最多失败次数。 */
  loginMaxAttempts: number
  /** 登录限速窗口（毫秒）。 */
  loginWindowMs: number
  /** 是否写审计日志。 */
  auditLog: boolean
  /** 密钥、用户库、临时上传与审计日志所在目录。 */
  stateDir: string
}

/** dsh home 目录（与 dsh 自身一致的解析顺序）。 */
export function dshHome(): string {
  const fromEnv = process.env['DSH_HOME']
  if (typeof fromEnv === 'string' && fromEnv.length > 0) return fromEnv
  return join(homedir(), '.dsh')
}

/** 默认配置。 */
export function defaultConfig(): GatewayConfig {
  return {
    gatewayHost: '127.0.0.1',
    gatewayPort: 3081,
    dshPort: 0,
    publicAuthority: '',
    workspaceRoot: '',
    adminPassword: '',
    sessionMaxAgeDays: 30,
    cookieSecure: true,
    maxUploadBytes: 2 * 1024 * 1024 * 1024,
    loginMaxAttempts: 10,
    loginWindowMs: 15 * 60 * 1000,
    auditLog: true,
    stateDir: join(dshHome(), 'dsh-remote-gateway'),
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}

function str(raw: Record<string, unknown>, key: string, fallback: string): string {
  const value = raw[key]
  return typeof value === 'string' ? value : fallback
}

function bool(raw: Record<string, unknown>, key: string, fallback: boolean): boolean {
  const value = raw[key]
  return typeof value === 'boolean' ? value : fallback
}

/** 取整数并钳制到 [min, max]；非法值回退默认。 */
function int(raw: Record<string, unknown>, key: string, fallback: number, min: number, max: number): number {
  const value = raw[key]
  if (typeof value !== 'number' || !Number.isFinite(value)) return fallback
  const truncated = Math.trunc(value)
  if (truncated < min) return min
  if (truncated > max) return max
  return truncated
}

/**
 * 校验并归一化插件行 config。未知键忽略，越界值钳制，非法值回退默认。
 * 绝不抛异常 —— 配置写错不应该让整棵 dsh 插件树加载失败。
 *
 * 环境变量 `DSH_GATEWAY_PASSWORD` 优先于 config.adminPassword（避免把密码写进
 * profile 配置文件）。CLI 写入的 scrypt 哈希优先级最高，见 auth/password.ts。
 */
export function normalizeConfig(raw: unknown): GatewayConfig {
  const base = defaultConfig()
  const raw0 = isRecord(raw) ? raw : {}

  const cfg: GatewayConfig = {
    gatewayHost: str(raw0, 'gatewayHost', base.gatewayHost),
    gatewayPort: int(raw0, 'gatewayPort', base.gatewayPort, 1, 65535),
    dshPort: int(raw0, 'dshPort', base.dshPort, 0, 65535),
    publicAuthority: str(raw0, 'publicAuthority', base.publicAuthority).trim(),
    workspaceRoot: str(raw0, 'workspaceRoot', base.workspaceRoot).trim(),
    adminPassword: str(raw0, 'adminPassword', base.adminPassword),
    sessionMaxAgeDays: int(raw0, 'sessionMaxAgeDays', base.sessionMaxAgeDays, 1, 3650),
    cookieSecure: bool(raw0, 'cookieSecure', base.cookieSecure),
    maxUploadBytes: int(raw0, 'maxUploadBytes', base.maxUploadBytes, 1024, Number.MAX_SAFE_INTEGER),
    loginMaxAttempts: int(raw0, 'loginMaxAttempts', base.loginMaxAttempts, 1, 100000),
    loginWindowMs: int(raw0, 'loginWindowMs', base.loginWindowMs, 1000, 30 * 24 * 3600 * 1000),
    auditLog: bool(raw0, 'auditLog', base.auditLog),
    stateDir: str(raw0, 'stateDir', base.stateDir).trim() || base.stateDir,
  }

  const envPassword = process.env['DSH_GATEWAY_PASSWORD']
  if (typeof envPassword === 'string' && envPassword.length > 0) cfg.adminPassword = envPassword

  // 安全不变量：网关绝不绑定非回环地址。写错就强制拉回，并留下告警。
  if (cfg.gatewayHost !== '127.0.0.1' && cfg.gatewayHost !== '::1' && cfg.gatewayHost !== 'localhost') {
    console.warn(
      `[dsh-remote-gateway] gatewayHost=${JSON.stringify(cfg.gatewayHost)} 不是回环地址；` +
        `已强制改回 127.0.0.1（暴露 dsh 等同于交出电脑控制权）`,
    )
    cfg.gatewayHost = '127.0.0.1'
  }

  return cfg
}
