# dsh 插件与认证机制 —— 实测笔记（0.1.5-rc.3 → 0.1.7-rc.2）

> 全部结论都来自对**已安装的** dsh 源码与 `--dump-config` 实跑结果的核对，不是推测。
> 凡引用处均给出绝对路径。这份笔记的价值在于：网上（以及本项目的原始需求文档里）
> 流传的很多说法是错的，照着错的做会浪费大量时间。
>
> **版本沿革**：初版核对的是 `0.1.5-rc.3`。后于 `0.1.7-rc.2` 上逐条复核，结论见
> 「八、0.1.7 复核」——插件运行时用到的 dsh API **一处未变**，唯一需要适配的是
> 插件清单的兼容性声明方式（`dsh.engines.dsh` 已失效，改为 `peerDependencies`）。

安装位置：`/root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh/`
包含包：`.../dsh/node_modules/@deepseek-ai/*`（0.1.5-rc.3 时 240 个，0.1.7-rc.2 时 283 个）
完整源码仓库：`/root/deepseek-harness`（**注意：该仓库停在 `0.1.0-rc.8`，不能用来核对
0.1.5/0.1.7 的行为，只能读已安装的编译产物**）

---

## 一、插件形态

**函数式插件**：模块 named-export `name` / `inject` / `apply(ctx, config)`。
（也支持对象插件与 `Service` 子类插件。）

```ts
export const name = 'my-plugin'
export const inject = ['webServer', 'connection']
export function apply(ctx, rawConfig) { /* ... */ }
```

### 三个必须知道的点

**1. 行级 `inject` 才是决定性的。**
`cordis.patch.yml` 的行上必须再写一次 `inject`。少任何一个服务，启动时会报
`cannot get property "xxx" without inject` 并让**整棵插件树加载失败**。
证据：`/root/.dsh/profiles/web/node_modules/dsh-git-vcs/cordis.patch.yml` 的注释明写
「loader **行级** inject（模块里 export 的 inject 管不到这一行）……少任何一个，启动时都会报
cannot get property "xxx" without inject 整棵树加载失败」。

**2. 插件清单**：`package.json` 的 `dsh.bundle.patch` 指到插件自己的 `cordis.patch.yml`。

```json
{
  "type": "module",
  "main": "lib/index.js",
  "exports": { ".": { "types": "./lib/index.d.ts", "default": "./lib/index.js" } },
  "dsh": {
    "engines": { "dsh": ">=0.1.5-rc.1" },
    "bundle": { "patch": "./cordis.patch.yml" },
    "client": { "platform": "web", "inject": ["@deepseek-ai/dsh-client-ui-sidebar-right"] }
  },
  "peerDependencies": { "@deepseek-ai/dsh": ">=0.1.5-rc.1" },
  "peerDependenciesMeta": { "@deepseek-ai/dsh": { "optional": true } }
}
```

`dsh.*` 的子键：`bundle.patch`、`client.{platform,inject}`、`engines.dsh`。
（`configTrees` 在 0.1.5/0.1.7 两版里都**没有任何读取方**，是死字段。）

**⚠️ 兼容性声明：`dsh.engines.dsh` 已失效，改用 `peerDependencies`（0.1.7 起）。**
对整个已安装的 0.1.7-rc.2 做全量 grep（`grep -rn "\.engines\b" $DSH --include=*.js`）
**零命中** —— `dsh.engines.dsh` 现在纯属注释性元数据，不产生任何校验。
0.1.7 真正的机制是 `peerDependencies` 里以 `@deepseek-ai/dsh` / `@deepseek-ai/dsh-`
开头的键，实现在 `dsh-app-boot/lib/index.js` 的 `evaluatePluginCompatibility()`：

- 清单里**没有** `peerDependencies` 字段 → 直接返回 `undefined`，**完全不校验**（静默放行）。
- 有该字段但不满足当前运行时 → 是**硬失败**，不是警告：
  - 加载期：`loadProfileDirectory()` 抛错，该 bundle 进 `skippedBundles`（插件整层不加载）。
  - 安装期：`dsh-plugin-manager` 在调 pnpm **之前**预检，直接拒绝安装（"nothing was installed"）。
- 版本比较用 `semver.satisfies(runtime, range, { includePrerelease: true })`；
  `workspace:^` / `workspace:~` / `workspace:*` 会被替换成当前运行时版本。
- 补救手段：`dsh plugin --profile <p> allow-version <name>@<ver> --accept-risk`，
  写进 profile 目录的 `compatibility.json`（精确 `包名@版本` → 允许的 dsh 版本数组）。

本插件的取值理由：`>=0.1.5-rc.1` 在 0.1.7-rc.2 下 `satisfies` 为真（已实测），
既拿到了 0.1.7+ 的强制校验，又不会因为 dsh 出补丁版就把自己锁死。
`peerDependenciesMeta.optional: true` 是必要的：它**不影响** dsh 侧的校验
（`evaluatePluginCompatibility` 只读 `peerDependencies`，不看 meta），
但能阻止 `npm install` 在插件目录里自动拉一份 `@deepseek-ai/dsh` 进来。
（profile 自己的 `pnpm-workspace.yaml` 已带 `autoInstallPeers: false`，pnpm 一侧本来就不会装。）

官方包用精确锁版（如 `"@deepseek-ai/dsh": "0.1.7-rc.2"`）；社区 bundle `dsh-git-vcs@0.1.2`
仍只写 `dsh.engines.dsh`，即**它目前也没有被强制校验**。

**3. ⚠️ 列进 `bundles` 之后，不要再 `insert` 同一行。**
把插件加进 profile 的 `dsh.profile.bundles` 之后，插件自己的 patch **已经**插入了一行。
此时 profile 的 `cordis.patch.yml` 只能**按 id 覆盖**：

```yaml
- id: remote-gateway        # 覆盖，不是 insert
  config:
    gatewayPort: 3081
```

再写 `- insert: [{id: remote-gateway, ...}]` 会报
`duplicate loader entry id: remote-gateway`，整棵树加载失败。
另外 patch 覆盖会**替换整行 config**，所以每个键都要重新写全
（`dsh-web-app/cordis.patch.yml` 的注释：「A patch replaces the targeted row's whole
`config`, so each row below restates every key it owns.」）。

### 清理：用 `ctx.effect()`

cordis 的 `Events` 接口里**没有**通用的 `dispose` / `ready` 事件（只有 `internal/*`）。
注册清理的正确方式是 `ctx.effect(fn)`，`fn` 返回释放函数。dsh 官方插件都这么写：

```js
// dsh-host-frontend-static/lib/index.js:87
ctx.effect(() => ctx.webServer.registerFallback(async (req, res) => { ... }))
// dsh-git-vcs/index.js:1406
ctx.effect(() => webServer.register({ ... }))
```

profile 的 `dsh.profile.patchReload: live` 会让插件热重载，所以清理必须真的关掉监听，
否则第二次加载会 `EADDRINUSE`。

---

## 二、`ctx.webServer` —— 路由注册

`dsh-host-webserver/lib/types/index.d.ts`：

```ts
register(route: { kind: 'exact' | 'prefix'; path: string; handler: (req, res) => void | Promise<void> }): () => void
registerUpgrade(route: { path: string; handler: (req, socket, head) => void | Promise<void> }): () => void
registerFallback(handler): () => void      // 只有一个席位
tapIndex(transform): () => void
readonly port: number                      // config.port 为 0 时是 OS 分配的端口
readonly host: string
```

- **传参是单个对象**，不是 `(path, handler)`。
- `registerFallback()` **只有一个席位**，已被 `dsh-host-frontend-static` 占用；
  第二次注册直接抛错（「One owner only — a second registration throws」）。
  **这条决定了本项目的架构**：插件无法插到 dsh 自己 `/api` 路由与 SPA fallback 的前面，
  所以认证网关必须**独立监听另一个端口**，由隧道指向它。
- `WebServer.Config.host` 只接受 `'127.0.0.1' | '0.0.0.0'`。

---

## 三、dsh 的浏览器认证（`/api` 的两道门）

`dsh-client-connection/lib/index.js`：

```js
requestRejection(request) {
  if (!isTrustedApiRequest(request, this.trustedHosts)) return 403
  return this.browserAuth.isAuthenticated(request) ? undefined : 401
}
```

### 第一道：Host/Origin 信任栅栏（403）

- `Host` **必需**，且必须是回环（`localhost` / `[::1]` / 任意 `127/8`）或配置的 `trustedHosts`。
- 带 `sec-fetch-site: cross-site` → 直接 403。
- `Origin` **可以省略**（`if (origin === void 0) return true`）；若存在，其 `.host` 必须等于 Host。
- 该包自己的注释写明：**「this fence is not an auth layer.」** —— 它不是认证。

### 第二道：authority 绑定的 HMAC cookie（401）

```js
function requestAuthority(headers) { return new URL(`http://${headers.host}`).host }
function cookieName(authority) {
  return 'dsh-auth-' + base64url(sha256(authority))
}
```

- cookie 值：`v1.<base64url(JSON payload)>.<base64url(HMAC-SHA256(secret, body))>`
- payload：`{ version: 1, authority, issuedAt, expiresAt }`，且 verify 时要求
  `payload.authority === authority`（**cookie 与精确的 Host 字符串绑定**）
- 属性：`Max-Age; Path=/; Expires; HttpOnly; SameSite=Strict`（**没有 Secure**）
- 签名密钥：32 字节，credential record `client-connection/browser-session`，
  payload `{version:1, secret:<base64url>}`，落在 `$DSH_HOME/.credentials.yaml`
- **没有 Bearer、没有 API key、没有 query 认证** —— 只有 cookie。

### 拿 cookie：launch token 兑换

`BrowserAuth.authorizeIndex()`：`GET /?token=<launchToken>`（method 必须 GET、pathname 必须
恰好是 `/`、恰好一个 `token` 参数、Host 可解析）→ **303 + `Set-Cookie`**。
不匹配则 401。

launch token 是每进程 `randomBytes(32)` 的 base64url，存在以 process owner 为键的 WeakMap 里。

**关键：不需要去解析 stdout。** `HostConnectionHandle` 上有公开方法
（`dsh-client-connection/lib/types/rpc.d.ts:152`，实现见 `lib/index.js:562`）：

```ts
authenticatedUrl(baseUrl: string): string
// → 'http://127.0.0.1:3080/?token=<processLaunchToken>'
```

`dsh-web-app/lib/index.js:198` 自己就是用它打印
`dsh web: http://127.0.0.1:3080/?token=...` 的。

### 绑定策略

`ConnectionConfig`（`dsh-client-connection/lib/types/index.d.ts`）：
`trustedHosts?`、`cookieMaxAgeDays?`（默认 30）、`maxRequestBodyBytes?`（默认 300 MiB）。

---

## 四、绑定地址

`--dump-config` 实跑结果：

```yaml
host: !!js ctx.webStartup.host ?? '127.0.0.1'
port: !!js ctx.webStartup.port ?? 3080
```

- `webServer` 只接受 `127.0.0.1` / `0.0.0.0`。
- CLI **硬拒绝** `--host 0.0.0.0`（`dsh-web-app/lib/startup.js`），原文：
  *"intentionally not supported yet for safety: it would expose remote code execution to the
  network; use 127.0.0.1 instead"*。
- 只有 LAN IP 字面量在所有接口绑定下才被自动信任（`resolveLanTrust`）。
- 另有 `--trusted-host` 显式声明。

---

## 五、被证伪的说法

| 原始需求文档里的说法 | 判定 | 依据 |
|---|---|---|
| `ctx.webServer.register()` 注册路由即可做网关 | **不完整** | 签名是对象形式；且 `registerFallback` 单席位，插件无法挡在 `/api` 与 SPA 之前 |
| 「插件代为持有 dsh 的 launch token」，需要解析 stdout | **不必要** | `ctx.connection.authenticatedUrl()` 是公开 API |
| launch token cookie 的 payload 是 `{username, issuedAt, expiresAt}` | **错** | 实际是 `{version, authority, issuedAt, expiresAt}`，且绑定 Host |
| dsh「拒绝绑定 0.0.0.0」需要 `dsh-public-access` 插件解决 | **CLI 确实拒绝**，但 `webServer` 本身支持 `0.0.0.0`；不需要那个插件 |
| 「cookie 需要设置 Secure」 | **对**（Cloudflare 终结 TLS 时浏览器看到的是 HTTPS），但 dsh 自己设的 cookie 并无 Secure |
| 参考实现 `dsh-auth-gate` / `dsh-teams` / `@ericality/dsh-fileserve` / `dsh-webapi` / `dsh-remote-plugin` / `dsh-mobile` / `dsh-plugin-framework` | **均未安装/不存在于本机** | 全部 `ls` 不存在；不可作为依据 |
| 「47 条 REST + SSE 路由」 | **错** | 实际 8 条 webServer 路由 + 1 条 WS upgrade + 5 条 `/api` 内 exact fetch 子路由 + **84** 个 JSON-RPC 端点 |
| `ctx.workspaceFiles` 有「远程命名空间」 | **部分对** | host 服务是 `ctx.workspaceFiles`，client 侧访问器是 `ctx.remote.workspaceFiles` |
| 事件名为 `assistant/chunk` | **错** | 全 transport 包零匹配；实时流是 `session/follow` 的 `{"type":"assistant-stream","frame":{"type":"chunk",...}}` |
| `session/send` | **不存在** | 发消息用 `session/prompt` |
| `/api/remote.mux` 是 WebSocket 端点 | **对** | `REMOTE_STREAM_MUX_PATH = "/api/remote.mux"` |

---

## 六、可用的服务名（`inject` 用）

`Context` 的 augmentation 由各包各自声明。本项目用到/确认存在的：

`webServer`（dsh-host-webserver）、`connection`（dsh-client-connection）、`credentials`、
`authorization`、`tools`、`systemPrompt`、`subprocess`、`fs`、`sandbox`、`sessions`、
`settings`、`workspaceFiles`、`remote`、`typert`、`jobs`、`skills`、`goal`、
`webServer` 之外还有 `frontend-static` 等等。

插件只需**声明自己真正读的**服务；多声明会让加载依赖不必要的服务。

---

## 七、如何在本机做真实集成测试

`~/.dsh` 不可写时（例如沙箱把 `/` 挂成只读），可以把 `DSH_HOME` 指到一个可写目录，
用**符号链接**借用真实 profile 的 `node_modules` —— 读取可以穿越符号链接，因此不需要
复制或重新安装任何依赖：

```
<可写目录>/profiles/node_modules -> /root/.dsh/profiles/node_modules
<可写目录>/profiles/web/
    cordis.yml / pnpm-workspace.yaml     (从真实 profile 复制)
    package.json                          (bundles 只留 base + web-app + 待测插件)
    cordis.patch.yml                      (按 id 覆盖插件 config)
    node_modules/<待测插件> -> <插件源码目录>
```

然后 `DSH_HOME=<可写目录> dsh --profile web --port <测试端口> --no-open` 即可在不碰
用户正在使用的 profile 的前提下真实启动一个带插件的实例。
完整脚本见 `scripts/e2e-setup.sh`。

---

## 八、0.1.7 复核（针对 0.1.7-rc.2）

对已安装的 `0.1.7-rc.2` 逐条复核本插件用到的每个 dsh API。
**结论：运行时接口一处未变，本插件在 0.1.7 上开箱可用，无需改代码。**
实测依据：`bash scripts/e2e-run.sh` → **95/95 通过**（真实 dsh 0.1.7-rc.2，非 mock）。

### 8.1 未变的接口（逐条核对过编译产物）

| 接口 | 位置 | 判定 |
|---|---|---|
| `webServer.register({kind,path,handler})` | `dsh-host-webserver/lib/types/index.d.ts` | 签名一致 |
| `webServer.registerUpgrade({path,handler})` | 同上 | 签名一致 |
| `webServer.port` / `.host` | 同上 | 一致 |
| `registerFallback()` 单席位 | 同上（"One owner only"） | 仍单席位，架构理由成立 |
| `ctx.connection.authenticatedUrl(baseUrl)` | `dsh-client-connection/lib/index.js:600` | 一致，仍返回 `?token=<launchToken>` |
| `ctx.effect(fn, label)` | cordis | 一致 |
| launch token 兑换：`GET /?token=` → 303 + `Set-Cookie` | `browser-auth.js` `authorizeIndex()` | 触发条件完全一致（GET + pathname `/` + 恰好一个 token + 可解析 authority） |
| cookie 名 `dsh-auth-` + base64url(sha256(authority)) | 同上 `cookieName()` | 一致 |
| cookie 值 `v1.<base64url(payload)>.<base64url(HMAC)>` | 同上 `encodeCookie()` | 一致 |
| `/api` 信任栅栏 | `isTrustedApiRequest()` | 一致：Host 必需且须回环/trusted；`sec-fetch-site: cross-site` → 拒；Origin 可省略，有则 `.host` 须等于 Host |
| `REMOTE_STREAM_MUX_PATH = "/api/remote.mux"` | `dsh-api-gateway/lib/index.js:12` | 一致 |
| `DEFAULT_MAX_REQUEST_BODY_BYTES = 300 MiB` | `dsh-client-connection/lib/index.js:25` | 一致 |
| `--host 0.0.0.0` 被 CLI 硬拒绝 | `dsh-web-app/lib/startup.js:40` | 仍拒绝，原文未变 |

### 8.2 变了但**不影响**本插件的地方

- **`authorizeIndex()` 的 303 `location` 从 `"/"` 改成 `"./"`**
  （配合新增的目录相对挂载）。本插件兑换 cookie 时**不跟随重定向**，只读 `set-cookie`
  响应头，所以无感。
- **`serveStatic()` 改了签名**：`authorizeIndex` 现在以无参 thunk 传入
  （`dsh-host-frontend-static/lib/index.js:95`），且 `renderIndex` 会往 index.html 注入
  `<base href="./">`。本插件不调用 `serveStatic`，只是反向代理 dsh 的响应；
  网关在根路径提供服务，`./` 解析为 `/`，行为等价。
- **包数量 240 → 283**，新增包与本插件无关；`dsh-api-remotes` 是内部 `ctx.remote`
  概念（Host 事件转发白名单），**不是**对外暴露服务的能力，不构成对本插件的替代。

### 8.3 唯一需要适配的：兼容性声明（已改）

`dsh.engines.dsh` 在 0.1.7 里**没有任何读取方**（全量 grep 零命中），
替换机制是 `peerDependencies`，且不满足时是硬失败而非警告。
本插件已补上 `peerDependencies` + `peerDependenciesMeta.optional`，理由与实测见
本文第一节「2. 插件清单」。改完后 `evaluatePluginCompatibility()` 对
`0.1.7-rc.2` 返回 `undefined`（= 通过校验），e2e 95/95 仍全过。

### 8.4 复核手法（可复用）

```bash
# 1) 确认某个 dsh.* 字段是否真的有人读（死字段检测）
grep -rn "\.engines\b" /root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh \
  --include=*.js | grep -v 'engines":'      # 零命中 = 死字段

# 2) 直接问 0.1.7 自己的兼容性判定函数，不要靠猜 semver
node --input-type=module -e "
const { evaluatePluginCompatibility } = await import(
  '/root/.nvm/versions/node/v24.19.0/lib/node_modules/@deepseek-ai/dsh/node_modules/@deepseek-ai/dsh-app-boot/lib/index.js');
const m = JSON.parse(require('fs').readFileSync('<插件>/package.json','utf8'));
console.log(evaluatePluginCompatibility(m, {}) ?? 'PASS');
"
```
