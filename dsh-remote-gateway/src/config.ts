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
  /**
   * 网关绑定地址。只允许**回环**或**具体的内网 IPv4 字面量**。
   *
   * 为什么放宽到内网 IP：隧道（cloudflared）的 ingress 指向一个具体 IP:端口
   * 比指向 127.0.0.1 更容易在控制台里辨认，也便于区分「这是网关」而不是别的
   * 本地服务。网关自带认证层且 fail-closed，所以内网可达并不等于把 dsh 交出去。
   *
   * 仍然**拒绝** `0.0.0.0` / `::`（全接口）与任何公网地址 —— 前者会一并暴露在
   * docker0、VPN 等所有接口上，是「本想开一个口子结果开了全部」的经典事故。
   */
  gatewayHost: string
  /** 网关监听端口；隧道 ingress 指向这里。0 表示由操作系统分配（测试/嵌入用）。 */
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
  /**
   * 反向代理**可重放**的请求体上限（字节）。
   *
   * 为什么需要它：dsh 重启后 launch token 与签名密钥都会变，缓存里的会话 cookie
   * 立刻失效。网关的做法是「失效 → 重新兑换 → 把请求重放一次」。重放要求请求体
   * 还能再发一遍，而流一旦被消费就没了 —— 所以只有被缓冲下来的体才可重放。
   *
   * 取值权衡：调大 = 更多请求能自愈，代价是每个并发请求占这么多内存；
   * 调小 = 省内存，但更大的请求在 dsh 重启时会失败。默认 64 MiB，
   * 覆盖常见的安装包/文档/图片；超过它的请求改为**流式转发**（仍然能传成功，
   * 只是失去重放能力，日志里会写明）。
   */
  replayBodyLimitBytes: number
  /** 登录限速：窗口内最多失败次数。 */
  loginMaxAttempts: number
  /** 登录限速窗口（毫秒）。 */
  loginWindowMs: number
  /** 是否写审计日志。 */
  auditLog: boolean
  /** 密钥、用户库、临时上传与审计日志所在目录。 */
  stateDir: string
}

/** 回环地址字面量。 */
const LOOPBACK_HOSTS = new Set(['127.0.0.1', '::1', 'localhost'])

/**
 * 判断一个地址是否是「具体的内网 IPv4 字面量」。
 *
 * 只接受 RFC1918 三个段里的**单个地址**：10/8、172.16/12、192.168/16。
 * 不接受 `0.0.0.0`（全接口）、不接受 CIDR、不接受主机名、不接受公网地址。
 */
function isPrivateIpv4(host: string): boolean {
  const parts = host.split('.')
  if (parts.length !== 4) return false
  const octets: number[] = []
  for (const part of parts) {
    // 只接受规范的十进制写法，挡掉 0x7f.1、010.1 这类会被其它解析器改写的形态。
    if (!/^\d{1,3}$/.test(part)) return false
    const value = Number(part)
    if (value > 255) return false
    octets.push(value)
  }
  const [a = -1, b = -1] = octets
  if (a === 10) return true
  if (a === 172 && b >= 16 && b <= 31) return true
  if (a === 192 && b === 168) return true
  return false
}

/** 该绑定地址是否需要发出「已超出回环」告警。 */
export function isLoopbackHost(host: string): boolean {
  return LOOPBACK_HOSTS.has(host) || host.startsWith('127.')
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
    replayBodyLimitBytes: 64 * 1024 * 1024,
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
    gatewayPort: int(raw0, 'gatewayPort', base.gatewayPort, 0, 65535),
    dshPort: int(raw0, 'dshPort', base.dshPort, 0, 65535),
    publicAuthority: str(raw0, 'publicAuthority', base.publicAuthority).trim(),
    workspaceRoot: str(raw0, 'workspaceRoot', base.workspaceRoot).trim(),
    adminPassword: str(raw0, 'adminPassword', base.adminPassword),
    sessionMaxAgeDays: int(raw0, 'sessionMaxAgeDays', base.sessionMaxAgeDays, 1, 3650),
    cookieSecure: bool(raw0, 'cookieSecure', base.cookieSecure),
    maxUploadBytes: int(raw0, 'maxUploadBytes', base.maxUploadBytes, 1024, Number.MAX_SAFE_INTEGER),
    replayBodyLimitBytes: int(raw0, 'replayBodyLimitBytes', base.replayBodyLimitBytes, 0, Number.MAX_SAFE_INTEGER),
    loginMaxAttempts: int(raw0, 'loginMaxAttempts', base.loginMaxAttempts, 1, 100000),
    loginWindowMs: int(raw0, 'loginWindowMs', base.loginWindowMs, 1000, 30 * 24 * 3600 * 1000),
    auditLog: bool(raw0, 'auditLog', base.auditLog),
    stateDir: str(raw0, 'stateDir', base.stateDir).trim() || base.stateDir,
  }

  const envPassword = process.env['DSH_GATEWAY_PASSWORD']
  if (typeof envPassword === 'string' && envPassword.length > 0) cfg.adminPassword = envPassword

  // 安全不变量：网关只允许绑回环或**具体的内网 IPv4**。其它一律拉回回环。
  //
  // 为什么内网 IP 可以放行：网关自带认证层且未配密码时 fail-closed，内网可达
  // 不等于把 dsh 交出去；而隧道 ingress 指向具体 IP 比指向 127.0.0.1 更容易
  // 在控制台里辨认。为什么 0.0.0.0 仍然拒绝：它会把服务一并挂到 docker0、VPN
  // 等所有接口上，是「只想开一个口子、结果开了全部」的典型事故。
  if (!isLoopbackHost(cfg.gatewayHost)) {
    if (isPrivateIpv4(cfg.gatewayHost)) {
      console.warn(
        `[dsh-remote-gateway] gatewayHost=${JSON.stringify(cfg.gatewayHost)} 是内网地址，` +
          `网关将对该网段可达（认证层仍然生效；dsh 自身仍只在回环）。`,
      )
    } else {
      console.warn(
        `[dsh-remote-gateway] gatewayHost=${JSON.stringify(cfg.gatewayHost)} 既不是回环、` +
          `也不是具体的内网 IPv4；已强制改回 127.0.0.1。` +
          `（若要经隧道暴露，请填本机内网地址，例如 192.168.1.182；` +
          `不要用 0.0.0.0，那会把服务挂到所有接口上）`,
      )
      cfg.gatewayHost = '127.0.0.1'
    }
  }

  return cfg
}
