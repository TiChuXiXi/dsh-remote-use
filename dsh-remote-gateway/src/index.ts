/**
 * dsh-remote-gateway —— dsh 手机远程控制网关插件。
 *
 * 职责：在 dsh **前面**放一个强制认证层。dsh 永远只监听 127.0.0.1，
 * 隧道 ingress 指向本插件的 3081 端口，所有外部流量都必须先过认证。
 *
 * 形态：cordis 函数式插件，导出 `name` / `inject` / `apply`。
 * **零运行时依赖** —— 运行时只 import node: 内置模块；ctx 由框架注入，
 * 类型在 src/types.ts 本地声明（理由见该文件）。
 *
 * 生命周期：用 `ctx.effect()` 注册监听与清理，这是 dsh 官方插件
 * （dsh-host-frontend-static、dsh-git-vcs）一致的写法。profile 开了
 * `patchReload: live`，所以清理必须真的关掉监听，否则热重载会 EADDRINUSE。
 */
import { defaultConfig, normalizeConfig } from './config.js'
import type { GatewayConfig } from './config.js'
import { AuditLog, errText, log, warn } from './log.js'
import { AuthRoutes } from './auth/routes.js'
import { PasswordStore } from './auth/password.js'
import { RateLimiter } from './auth/ratelimit.js'
import { TokenService } from './auth/token.js'
import { PathGuard } from './files/guard.js'
import { FsRoutes } from './files/routes.js'
import { DshSessionCookie } from './proxy/dsh-cookie.js'
import { GatewayServer } from './proxy/gateway.js'
import type { PluginContext } from './types.js'

/** 稳定插件名（与 cordis.patch.yml 的 id 对应）。 */
export const name = 'remote-gateway'

/**
 * 模块级依赖声明。**注意**：cordis.patch.yml 的行级 `inject` 才是决定性的，
 * 两处都要写。
 */
export const inject = ['webServer', 'connection']

/** 一个已装配好但尚未启动的网关。 */
export interface GatewayHandle {
  readonly config: GatewayConfig
  readonly dshPort: number
  readonly workspaceRoot: string
  readonly files: FsRoutes
  readonly auth: AuthRoutes
  /** 启动监听，返回实际端口。 */
  start(): Promise<number>
  /** 关闭监听并清理定时器。 */
  stop(): Promise<void>
}

/** 装配参数。 */
export interface GatewayOptions {
  config: GatewayConfig
  /** dsh 自身监听端口。 */
  dshPort: number
  /** 工作区根目录（绝对路径）。 */
  workspaceRoot: string
  /** 由宿主提供的 launch-token URL 解析器；测试时可注入桩函数。 */
  resolveTokenUrl: (baseUrl: string) => string
}

/**
 * 装配一个网关实例。与 cordis 解耦，便于单元测试与独立运行。
 */
export function createGateway(options: GatewayOptions): GatewayHandle {
  const { config, dshPort, workspaceRoot } = options
  if (!Number.isInteger(dshPort) || dshPort <= 0 || dshPort > 65535) {
    throw new Error(`dshPort 非法: ${String(dshPort)}（应为 1-65535）`)
  }

  const audit = new AuditLog(config.stateDir, config.auditLog)
  const passwords = new PasswordStore(config.stateDir)
  const tokens = new TokenService(config.stateDir, config.sessionMaxAgeDays * 24 * 60 * 60 * 1000)
  const limiter = new RateLimiter(config.loginMaxAttempts, config.loginWindowMs)
  const guard = new PathGuard(workspaceRoot)

  const auth = new AuthRoutes({ config, passwords, tokens, limiter, audit })
  const files = new FsRoutes({ config, guard, audit })
  const cookie = new DshSessionCookie(dshPort, options.resolveTokenUrl, (kind, detail) => {
    if (kind === 'error') warn(detail)
    else log(detail)
  })

  const server = new GatewayServer({ config, dshPort, auth, files, cookie, audit })

  let sweepTimer: NodeJS.Timeout | null = null

  return {
    config,
    dshPort,
    workspaceRoot,
    files,
    auth,
    async start(): Promise<number> {
      const port = await server.start()
      // 定期清理超过 24h 未活动的分块上传暂存目录。
      sweepTimer = setInterval(
        () => {
          void files.sweepStaleChunkSessions().then((removed) => {
            if (removed > 0) log(`已清理 ${String(removed)} 个过期分块上传会话`)
          })
        },
        60 * 60 * 1000,
      )
      sweepTimer.unref()
      // 顺带清理一次历史吊销记录。
      tokens.pruneEpochs()
      return port
    },
    async stop(): Promise<void> {
      if (sweepTimer !== null) {
        clearInterval(sweepTimer)
        sweepTimer = null
      }
      await server.stop()
    },
  }
}

/**
 * cordis 插件入口。
 *
 * @param ctx - 宿主 context，必须已注入 webServer 与 connection。
 * @param rawConfig - 插件行的 config，未经校验。
 */
export function apply(ctx: PluginContext, rawConfig: unknown): void {
  const config = normalizeConfig(rawConfig)

  // dshPort: 0 表示「用 dsh 实际监听的端口」。webServer 已注入，port 必定可用。
  const dshPort = config.dshPort > 0 ? config.dshPort : ctx.webServer.port
  const workspaceRoot = config.workspaceRoot.length > 0 ? config.workspaceRoot : process.cwd()

  let handle: GatewayHandle
  try {
    handle = createGateway({
      config,
      dshPort,
      workspaceRoot,
      // ctx.connection.authenticatedUrl 是 dsh 自己在 web-app 里用来打印
      // `dsh web: http://127.0.0.1:3080/?token=...` 的公开方法。
      resolveTokenUrl: (baseUrl) => ctx.connection.authenticatedUrl(baseUrl),
    })
  } catch (error) {
    warn(`网关装配失败，插件未生效: ${errText(error)}`)
    return
  }

  if (!handle.auth.isConfigured()) {
    warn(
      '尚未设置网关密码 —— 已进入 fail-closed 模式：除 /gateway/health 外全部返回 503。\n' +
        '  请执行其一：\n' +
        '    1) npx dsh-remote-gateway set-password\n' +
        '    2) 在插件行 config 里设置 adminPassword（首次登录后自动落库为 scrypt 哈希）\n' +
        '    3) 启动 dsh 前 export DSH_GATEWAY_PASSWORD=...',
    )
  }

  ctx.effect(() => {
    let stopped = false
    const stop = (): void => {
      if (stopped) return
      stopped = true
      void handle.stop().catch((error: unknown) => {
        warn(`关闭网关失败: ${errText(error)}`)
      })
    }

    handle
      .start()
      .then((port) => {
        const publicHint = config.publicAuthority.length > 0 ? ` 公网: https://${config.publicAuthority}` : ''
        log(
          `网关已启动: http://${config.gatewayHost}:${String(port)}` +
            `  →  反向代理 127.0.0.1:${String(dshPort)}${publicHint}`,
        )
        log(`工作区根: ${workspaceRoot}`)
        log(`审计日志: ${config.auditLog ? handle.files.staging.replace(/\/uploads$/u, '/audit.log') : '已关闭'}`)
      })
      .catch((error: unknown) => {
        warn(
          `网关监听失败（端口 ${String(config.gatewayPort)} 可能已被占用）: ${errText(error)}。` +
            `插件其余部分未生效。`,
        )
      })

    return stop
  }, 'dsh-remote-gateway/server')

  log('插件已加载，等待网关监听完成')
}

/** 供测试与 CLI 复用的默认配置工厂。 */
export { defaultConfig }

export type { GatewayConfig }
