/**
 * 本插件依赖的 dsh 宿主 API —— 只声明我真正用到的那一小块。
 *
 * 为什么自己声明而 from '@deepseek-ai/...' 导入：
 *   1. 这些包在 npm 上的公开版本（0.0.1-rc.1）远落后于随 dsh 安装的 0.1.5-rc.3，
 *      依赖 npm 版本会拿到过时甚至不存在的签名。
 *   2. 插件被 cordis 从 profile 目录加载，运行时不需要 import 任何东西 ——
 *      ctx 由框架传入。把类型声明在本地，本包就真正做到**零运行时依赖**。
 *   3. tsconfig 里不需要 paths 硬编码机器绝对路径，插件可移植。
 *
 * 每一处签名都对照已安装的 dsh 0.1.5-rc.3 的 .d.ts 与编译产物核实过：
 *   - dsh-host-webserver/lib/types/index.d.ts      → WebRoute / WebUpgradeRoute / WebServer
 *   - dsh-client-connection/lib/types/rpc-host.d.ts → HostConnectionHandle.authenticatedUrl
 */
import type { IncomingMessage, ServerResponse } from 'node:http'
import type { Duplex } from 'node:stream'

/** 路由匹配方式：'exact' 逐字匹配 pathname；'prefix' 匹配 p 与 p/<任意>。 */
export type WebRouteKind = 'exact' | 'prefix'

/** 一条具名路由。handler 拥有完整的响应生命周期。 */
export interface WebRoute {
  kind: WebRouteKind
  /** 绝对 pathname，结尾不带斜杠。 */
  path: string
  handler: (req: IncomingMessage, res: ServerResponse) => void | Promise<void>
}

/** 一条精确路径的 HTTP upgrade 注册。 */
export interface WebUpgradeRoute {
  /** 绝对 pathname，结尾不带斜杠。 */
  path: string
  /** 拥有协议协商与升级后 socket 的使用权。 */
  handler: (req: IncomingMessage, socket: Duplex, head: Buffer) => void | Promise<void>
}

/** ctx.webServer —— dsh 的浏览器 HTTP 载体服务。 */
export interface WebServerService {
  /** 实际监听端口（config.port 为 0 时是操作系统分配的端口）。 */
  readonly port: number
  /** 已配置的绑定地址。 */
  readonly host: string
  register(route: WebRoute): () => void
  registerUpgrade(route: WebUpgradeRoute): () => void
}

/** ctx.connection —— 浏览器会话载体；authenticatedUrl 是拿 launch token 的公开途径。 */
export interface ConnectionService {
  /**
   * 把本进程的 launch token 加到干净的 Web 应用 URL 上。
   * @param baseUrl 规范化的浏览器 origin，例如 'http://127.0.0.1:3080/'
   * @returns 形如 'http://127.0.0.1:3080/?token=<processLaunchToken>'
   */
  authenticatedUrl(baseUrl: string): string
}

/** effect 体可接受的返回值：一个释放函数（或它们的 Promise）。 */
export type EffectResult = void | Disposable | Promise<void | Disposable>

/** 本插件用到的宿主 Context 面。 */
export interface PluginContext {
  webServer: WebServerService
  connection: ConnectionService
  /**
   * 在所属 fiber 上注册一个带清理的 effect；fiber 卸载时按逆序执行返回的释放函数。
   * 这是 dsh 官方插件的标准清理写法（dsh-host-frontend-static、dsh-git-vcs 都这么用）。
   */
  effect(execute: () => EffectResult, label?: string): () => void
}

/** 释放函数。 */
export type Disposable = () => void
