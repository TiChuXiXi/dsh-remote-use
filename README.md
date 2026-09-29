# dsh 手机远程控制

让手机通过公网安全访问家用电脑上的 [DeepSeek Harness](https://github.com/deepseek-ai/deepseek-harness)（dsh）：看会话、实时对话、传文件。

**核心安全前提：dsh 永远只监听 `127.0.0.1`。** 所有外部流量必须先经过本项目的认证网关，未登录一律拿不到任何 dsh 能力。

```
手机 App / 手机浏览器
        │ HTTPS / WSS
        ▼
Cloudflare 边缘  ──Named Tunnel──▶  cloudflared（本机）
                                        │ http://127.0.0.1:3081
                                        ▼
                            ┌───────────────────────────┐
                            │  dsh-remote-gateway 插件   │
                            │  · 用户名/密码认证 + 限速   │
                            │  · 审计日志 + 会话吊销      │
                            │  · /fs/* 工作区文件服务     │
                            └───────────┬───────────────┘
                                        │ 反向代理 + 注入 dsh 凭据
                                        │ 并改写 Host → 127.0.0.1:3080
                                        ▼
                            dsh web  127.0.0.1:3080（只监听回环）
```

---

## 目录结构

| 路径 | 内容 |
|---|---|
| `dsh-remote-gateway/` | dsh 插件（TypeScript，零运行时依赖）：认证网关 + 反向代理 + 文件服务 |
| `android/` | Android App（纯 Java 源码；两条构建流水线：无 Gradle 的 `aapt2+d8+apksigner` 与 Gradle/AGP；UI 层有 Robolectric 测试） |
| `docs/GATEWAY-API.md` | **冻结的网关 HTTP 契约**，插件与 App 的共同依据 |
| `docs/PLUGIN-NOTES.md` | dsh 插件/认证机制实测笔记（0.1.5-rc.3 初版 + **0.1.7-rc.2 复核**，含大量被证伪的传言） |
| `docs/dsh-transport-spec.md` | dsh 自身线上协议实测规格（84 个 RPC 端点 + WebSocket mux） |
| `scripts/verify-all.sh` | 一条命令跑完全部可复现验证（编译 / 单测 / Robolectric / 真实 dsh 端到端 / 大文件 / 内网绑定） |
| `scripts/e2e-verify.sh` | 95 项端到端验收检查 |
| `scripts/e2e-largefile.sh` | 大文件专项：上传三模式 + 经代理的大上传 + Range 续传（20 项） |
| `scripts/verify-lan-bind.sh` | 内网绑定与「dsh 未被暴露」验证（10 项） |
| `scripts/tunnel-up.sh` | 起 Cloudflare Named Tunnel（已内置 SRV 规避） |
| `scripts/e2e-public.sh` | 经 Cloudflare Quick Tunnel 的公网链路验收 |

---

## 快速开始

### 1. 构建插件

```bash
cd dsh-remote-gateway
npm install
npm run build          # tsc → lib/
npm test               # 68 项单元测试
```

### 2. 设置网关密码

```bash
npx dsh-remote-gateway set-password          # 交互式，密码只从 stdin 读
# 或
npx dsh-remote-gateway list-users
```

密码以 **scrypt**（N=16384, r=8, p=1）哈希存到 `$DSH_HOME/dsh-remote-gateway/users.json`（权限 600），明文不落盘。

### 3. 安装到 dsh profile

插件是标准 dsh bundle。把包目录加进 profile：

```bash
dsh plugin --profile web add /绝对路径/dsh-remote-gateway
```

然后确认 profile 的 `package.json` 里 `dsh.profile.bundles` 含 `dsh-remote-gateway`：

```json
{
  "dsh": {
    "profile": {
      "bundles": ["@deepseek-ai/dsh-base", "@deepseek-ai/dsh-web-app", "dsh-remote-gateway"]
    }
  }
}
```

启动 dsh，看到这两行即成功：

```
[dsh-remote-gateway] 网关已启动: http://127.0.0.1:3081  →  反向代理 127.0.0.1:3080
[dsh-remote-gateway] 工作区根: /your/workspace
```

#### dsh 版本兼容性（0.1.7 起）

插件的 `package.json` 里同时声明了两处，**两者的效力完全不同**：

```json
"dsh":            { "engines": { "dsh": ">=0.1.5-rc.1" } },
"peerDependencies":     { "@deepseek-ai/dsh": ">=0.1.5-rc.1" },
"peerDependenciesMeta": { "@deepseek-ai/dsh": { "optional": true } }
```

- `dsh.engines.dsh` —— **0.1.7 里已无任何读取方**（对整份安装做全量 grep 零命中），
  纯注释性元数据。保留它只是为了兼容可能读过它的旧版本。
- `peerDependencies` —— 0.1.7 真正生效的机制。不满足当前 dsh 版本时是**硬失败**：
  加载期该 bundle 整层不加载，安装期直接拒绝安装。用
  `dsh plugin --profile web allow-version <name>@<ver> --accept-risk` 可对**精确版本**放行。
- `optional: true` 是必要的：dsh 侧的校验只读 `peerDependencies`、不看 meta，所以校验照旧；
  而它能让 `npm install` 不在插件目录里自动拉一份 `@deepseek-ai/dsh` 进来。

当前取值 `>=0.1.5-rc.1` 已实测在 `0.1.7-rc.2` 下通过校验。细节见 `docs/PLUGIN-NOTES.md` 第一、八节。

### 4. 本地自查

```bash
curl -s http://127.0.0.1:3081/gateway/health
# {"ok":true,"service":"dsh-remote-gateway","version":"1.0.0","configured":true,"uptimeMs":...}

# 未登录必须被拒
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:3081/api/session/list   # → 401

# 登录拿 token
TOKEN=$(curl -s -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"你的密码"}' \
  http://127.0.0.1:3081/gateway/login | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).token))')

curl -s -H "Authorization: Bearer $TOKEN" 'http://127.0.0.1:3081/fs/list?path=.'
```

浏览器打开 `http://127.0.0.1:3081/` 会先看到登录页，登录后是完整的 dsh 界面。

---

## 配置项

改在 profile 的 `cordis.patch.yml` 里，**按 id 覆盖**（不要再 `insert` —— 插件作为 bundle 已经插入过这一行，重复 insert 会报 `duplicate loader entry id` 并让整棵插件树加载失败）：

```yaml
- id: remote-gateway
  config:
    gatewayHost: 192.168.1.182  # 网关绑定地址：回环 或 具体内网 IPv4（见下）
    gatewayPort: 3081        # 网关端口，隧道 ingress 指向它
    dshPort: 0               # 0 = 自动使用 dsh 实际监听端口
    publicAuthority: ''      # 公网域名[:端口]，仅用于日志
    workspaceRoot: ''        # 空 = dsh 进程 cwd
    cookieSecure: true       # 走 Cloudflare（HTTPS）时必须 true
    maxUploadBytes: 2147483648      # 单个请求体上限（2 GiB）
    replayBodyLimitBytes: 67108864  # 反向代理可重放的请求体上限（64 MiB，见下）
    loginMaxAttempts: 10     # 15 分钟窗口内失败次数上限
    loginWindowMs: 900000
    auditLog: true
    stateDir: ''             # 空 = $DSH_HOME/dsh-remote-gateway
```

> patch 覆盖会替换**整行** config，所以每个键都要写全。

### `gatewayHost` 能填什么

允许**回环**（`127.0.0.1` / `::1` / `localhost`）或**具体的内网 IPv4 字面量**（`10/8`、`172.16/12`、`192.168/16` 三段内的单个地址）。

推荐填本机内网地址（本机是 `192.168.1.182`）：隧道 Public Hostname 里写 `192.168.1.182:3081` 一眼就能认出「这是网关」，不会和别的本地服务混淆。

**仍然拒绝 `0.0.0.0` / `::`**，会被强制拉回 `127.0.0.1` 并打印告警 —— 全接口绑定会把服务一并挂到 `docker0`、VPN 等所有接口上，是「只想开一个口子、结果开了全部」的典型事故。公网地址、CIDR、主机名同样拒绝。

> dsh 自身**始终只在回环**，这一点不受本配置影响：CLI 硬拒绝 `--host 0.0.0.0`，网关也只把上游固定指向 `127.0.0.1:<dshPort>`。
> `scripts/verify-lan-bind.sh` 会实测这三条：内网 IP 上网关可达、`127.0.0.1` 上网关**不可达**（证明没绑 0.0.0.0）、内网 IP 上 **dsh 不可达**。

### `replayBodyLimitBytes` 管什么（大文件必读）

dsh 重启后 launch token 与签名密钥都会变，缓存里的会话 cookie 立刻失效。网关的处理是「换 cookie → **把请求重放一次**」。重放要求请求体还能再发一遍，而流一旦被消费就没了 —— 所以**只有被缓冲下来的请求体才可重放**。

- **≤ 该值**：请求体读进内存，401 时能自动换 cookie 并重放，客户端无感。
- **> 该值**：改为**流式转发**。**上传照样成功**，只是失去重放能力；万一此时吃 401，网关会返回 `502 upstream_reauth_needed` 让客户端重传（会话已刷新，重传即成功），而不是悄悄发一个空体上去。

调大 = 更多请求能自愈，代价是每个并发请求占这么多内存；调小 = 省内存。

**真正决定「能不能传」的是 `maxUploadBytes`，不是这个值。** 实测：256 MiB 经反向代理上传成功（该体积已超过 64 MiB 阈值，走的就是流式转发路径）。

---

## 公网暴露（Cloudflare Named Tunnel）

### ingress 规则

在 Cloudflare Zero Trust 控制台 → Networks → Tunnels → 选中隧道 → Public Hostname → Add：

```
Subdomain : 你想要的子域
Domain    : 你的域名
Type      : HTTP
URL       : 192.168.1.182:3081        ← 填网关绑定的内网 IP:端口
```

**绝不要指向 3080。** 指向 3080 会让 `/api` 的流量绕过认证层，审计与限速一并失效。

填内网 IP（`192.168.1.182:3081`）而不是 `127.0.0.1:3081`，是为了在控制台里一眼分辨出「这条指向网关」。
`gatewayHost` 改成什么，这里就填什么 —— 两者必须一致，否则边缘连不上源站。

**当前已配置**（2026-09-29 实测生效）：

| 顺序 | 目标 | 类型 | 服务 |
|---|---|---|---|
| 1 | `remote.jbt99.site` | 已发布的应用程序 | `http://192.168.1.182:3081` |

入口即 `https://remote.jbt99.site`。隧道 ID `a8835b18-d80d-4187-ab7d-4f00510a59ac`。

### 关闭 HTTP/2 与 HTTP/3（重要）

在域名 → Network 设置里关闭 **HTTP/2** 和 **HTTP/3**，强制浏览器与边缘之间走 HTTP/1.1。

原因：HTTP/2 不支持 `Upgrade: websocket`，它用 RFC 8441 的 Extended CONNECT；而 Cloudflare Tunnel 的 HTTP origin service 不会把 Extended CONNECT 翻译成 HTTP/1.1 Upgrade，导致 dsh 的 `/api/remote.mux` 永远建不起来 —— 表现是**界面能开、但发消息没有任何流式输出**。

### 启动隧道

```bash
bash scripts/tunnel-up.sh --daemon     # 后台跑，日志写 .runtime/cf-named.log
bash scripts/tunnel-up.sh              # 前台跑，Ctrl-C 退出
```

脚本已内置下面那条 `TUNNEL_EDGE` 规避措施。token 从 `$CF_TUNNEL_TOKEN` 或 `.runtime/cf-token` 读。

手动跑等价于：

```bash
TUNNEL_EDGE=region1.v2.argotunnel.com:7844,region2.v2.argotunnel.com:7844 \
  cloudflared tunnel run --token <你的隧道token>
```

**本机没有 systemd**（PID 1 是 `bwrap`），所以 `cloudflared service install` 装出来的服务起不来，只能直接跑进程。

### 本机特有的排障：SRV 解析失败

如果日志出现：

```
ERR edge discovery: error looking up Cloudflare edge IPs: the DNS query failed
    error="lookup _v2-origintunneld._tcp.argotunnel.com on 192.168.1.1:53: ... i/o timeout"
```

说明本机 DNS 解析器**不支持 SRV 查询**（实测 `dig SRV _v2-origintunneld._tcp.argotunnel.com` 返回 `SERVFAIL, recursion requested but not available`）。cloudflared 靠 SRV 做边缘发现，因此会一直重试却连不上。

**解决办法：显式指定边缘地址，绕过 SRV 发现。**

```bash
TUNNEL_EDGE=region1.v2.argotunnel.com:7844,region2.v2.argotunnel.com:7844 \
  cloudflared tunnel run --token <你的隧道token>
```

连上后会看到：

```
INF Registered tunnel connection connIndex=0 ... location=hkg01 protocol=quic
INF Updated to new configuration config="{\"ingress\":[{\"hostname\":\"...
```

如果装成 systemd 服务，需要把该环境变量写进 unit：

```bash
sudo systemctl edit cloudflared
```

```ini
[Service]
Environment=TUNNEL_EDGE=region1.v2.argotunnel.com:7844,region2.v2.argotunnel.com:7844
```

（本机实测：QUIC/UDP 7844 通；HTTP/2 的 TCP 443 到边缘被中间透明代理挡住，所以用默认的 QUIC，不要设 `TUNNEL_TRANSPORT_PROTOCOL=http2`。）

### 验证

```bash
# 1) 未登录应被网关拒绝
curl -s -o /dev/null -w '%{http_code}\n' https://www.你的域名/api/session/list   # → 401

# 2) 浏览器打开 https://www.你的域名/ → 登录页

# 3) 登录后进 dsh 界面，发一条消息看流式输出
#    DevTools → Network → WS → /api/remote.mux 状态应为 101
```

命令行验证 WebSocket 真实往返（不只是看 101）：

```bash
TOKEN=$(curl -s -X POST -H 'content-type: application/json' \
  -d '{"username":"admin","password":"你的密码"}' \
  https://www.你的域名/gateway/login | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>console.log(JSON.parse(s).token))')

node dsh-remote-gateway/tests/ws-probe.mjs wss://www.你的域名/api/remote.mux "$TOKEN" '$events'
# ✓ 101 Switching Protocols，Sec-WebSocket-Accept 校验通过
# ✓ 收到文本帧: {"type":"item","streamId":"probe1","value":{"type":"ready",...}}
```

---

## 关键设计决策

### 为什么网关独立监听 3081，而不是把路由注册到 dsh 的 3080

dsh 的 `/api` 前缀路由和 SPA fallback 由 dsh 自己注册，插件无法插到它们**前面**；而且 `webServer.registerFallback()` 只有一个席位，已被 `frontend-static` 占用，第二次注册直接抛错（源码已核实）。若隧道指向 3080，`/api` 的流量就绕过了本插件的认证层，审计与限速一并失效。独立监听才能真正做到「所有外部流量必须经过插件认证」。

### 插件怎么拿到 dsh 的凭据

dsh 的 `/api` 有两道门：Host/Origin 信任栅栏（403）+ authority 绑定的 HMAC cookie 认证（401）。cookie 名是 `dsh-auth-` + base64url(sha256(authority))，签名载荷里的 `authority` 必须等于 Host。（0.1.5-rc.3 与 0.1.7-rc.2 两版逐条核对，这两道门的实现**完全一致**。）

插件与 dsh **同进程**，因此可以直接用 dsh 的公开 API 拿 launch token：

```js
ctx.connection.authenticatedUrl(`http://127.0.0.1:${dshPort}/`)
// → "http://127.0.0.1:3080/?token=<processLaunchToken>"
```

（dsh-web-app 自己也是用它打印 `dsh web: http://127.0.0.1:3080/?token=...` 的。**不需要**去解析 stdout。）

然后 `GET /?token=<launchToken>` + `Host: 127.0.0.1:3080` → 303 + `Set-Cookie`，抓下来缓存，注入到每个转发请求。

因为固定用回环 authority 面对 dsh，**永远通过信任栅栏**，不需要给 dsh 配 `trustedHosts`。

**自愈**：dsh 重启后 launch token 与签名密钥都变了，上游会返 401；此时网关自动失效缓存、重新兑换、重放一次该请求（请求体在 1 MiB 内或方法是幂等的才重放）。

### 为什么不用 dsh 原生的 launch-token 登录

launch token 是**进程级、不可吊销、且要放进 URL** 的一次性凭据。网关需要的是可吊销、可按用户区分、能放进 HttpOnly cookie 的凭据，所以自己实现了 scrypt + HMAC-SHA256 的会话层。

---

## 验证状态

**已真实验证**（`bash scripts/e2e-run.sh` → **95/95 通过**，打的是真实 dsh **0.1.7-rc.2**，不是 mock）：

- 未配置密码时 fail-closed：除 `/gateway/health` 与登录页外一律 503
- 登录：正确密码 200 + `Set-Cookie`（HttpOnly + SameSite=Strict）；错误密码与不存在的用户返回**完全相同**的 401；非 JSON 体 400
- Bearer 与 cookie 等价；篡改/伪造签名的 token 一律 401
- 登录限速：第 11 次失败尝试 429，且不影响其它来源
- 反向代理：真实 dsh SPA 返回 200；`POST /api/session/list` 返回 `result.ok=true`（证明 launch token → cookie 兑换成功）；dsh 自己的 415 校验被正确透传
- WebSocket：`/api/remote.mux` 升级 101 + `Sec-WebSocket-Accept` 校验 + **真实数据帧往返**（`$events` → `ready` 帧）；未认证升级被拒 401
- 文件：列表/MIME、下载、`Range` 206、**分块续传拼接后与原文件逐字节一致**、越界 Range 416
- 上传三种模式（原始体/分块/multipart）全部成功；未收齐分块时目标路径**无半成品**（原子落盘）；SHA-256 不匹配被拒
- 上传目标为**已存在目录**（`path=.` / `path=docs`）时按契约落成 `<dir>/<上传文件名>`；`X-File-Name` 里的目录成分被剥掉，无法借此逃逸
- 路径越界：`..`、编码穿越、绝对路径、`....//`、结尾点/空格、**软链文件与软链目录逃逸**全部 403
- 安全不变量：网关只监听配置的地址；dsh 直连仍需认证；`users.json`/`session.key` 权限 600 且无明文；审计日志五类事件齐全
- 登出**真的吊销该 token**（Bearer 立刻 401，不只是清 cookie）；`revoke-all` 后旧 token 立即失效、新 token 可用；`revoke-all` 后**同一毫秒内重登**的新 token 也可用（竞态回归）
- 安装命令 `dsh plugin --profile web add <插件目录>` 实测生效（写入 `link:` 依赖并在 profile `node_modules` 建链）

**大文件专项**（`bash scripts/e2e-largefile.sh` → **20/20 通过**，打真实 dsh）：

| 项目 | 体积 | 结果 |
|---|---|---|
| 模式 C 原始体上传 | **512 MiB** | sha256 一致、落盘逐字节一致 |
| 模式 A multipart 上传 | **256 MiB** | sha256 一致、落盘逐字节一致 |
| 模式 B 分块上传（4 块） | **512 MiB** | `completed=true`、合并结果逐字节一致 |
| **经反向代理**上传到 dsh 自己的端点 | **256 MiB** | dsh 收到 **268435456** 字节，完整未截断 |
| 下载 + Range 从 60% 续传 | **512 MiB** | 两段拼接 sha256 与源文件一致 |
| 内存 | 累计处理约 1.5 GiB | dsh RSS 仅增长 **83 MiB** → 流式转发/落盘，无整体缓冲 |

**反向代理重放语义**（`tests/proxy-replay.test.mjs` → **4/4 通过**，起假上游真实代理）：可重放体积内 401 → 换 cookie → 重放且**带完整请求体**；超过阈值 → **不得发出空体重放**，返回 `502 upstream_reauth_needed` 并记审计。

**内网绑定**（`bash scripts/verify-lan-bind.sh` → **10/10 通过**）：网关在内网 IP 上可达；`127.0.0.1` 上**不可达**（证明没绑 `0.0.0.0`）；内网 IP 上 **dsh 不可达**（dsh 未被暴露）；换绑定地址后认证层照旧生效（未登录 `/api/*` 401、浏览器 302）；经内网 IP 登录并走反向代理 200。

**单元测试** `npm test` → **72/72 通过**（路径守卫、token 篡改与吊销、密码策略、Range、限速、MIME、cookie 序列化、反向代理重放）。

**Android**：`android/dist/DshRemote-debug.apk` 构建成功（`com.dsh.remote`，minSdk 26，targetSdk 34，compileSdk 35，v1+v2+v3 签名，`resources.arsc`/`classes.dex` 均 STORED 且 4 字节对齐）。

App 的**网络层**（`android/java/com/dsh/remote/net/`，纯 Java、零 `android.*` 依赖）已在 JVM 上对**本项目的真实网关 + 真实 dsh** 跑通 **15/15 全过**：

```
PASS  login → /gateway/session 200
PASS  session/list（真实会话）→ session/create 拿到真实 sessionId
PASS  opened $events stream / session/follow stream
PASS  session/prompt 被接受 → 收到 turn/end
PASS  收到 2 个真实 assistant-stream 分片，实时文本 "PONG-HARNESS"（真实模型输出穿透整条链路）
PASS  /fs/list 解析（10 个条目）
PASS  multipart 上传 → 完整下载（sha256 一致）→ Range 断点续传（字节完全一致）
PASS  logout 后 token 立即失效（401）
```

运行方式：`bash android/verify/run_verify.sh --url http://127.0.0.1:3081 --user admin --pass '<密码>'`

**公网链路（正式域名，当前生产入口）**：`bash scripts/verify-public-named.sh` → **95/95 + 14/14 全部通过**，打的是真实域名 `https://remote.jbt99.site`：

```
公网入口: https://remote.jbt99.site      （Named Tunnel → 192.168.1.182:3081）
隧道: 2 条 QUIC 边缘连接已注册（hkg01 / hkg13）
  Android 网络层打公网 HTTPS/WSS（含真实 TLS）  → 14/14 通过
  公网端到端验收（95 项）                       → 95/95 通过
公网链路全部通过 ✓
```

前置：`bash scripts/prod-up.sh --daemon` + `bash scripts/tunnel-up.sh --daemon`。

> ⚠ 该套件最后的「登录限速」小节会**故意**打满你真实公网 IP 的失败计数桶，之后约 15 分钟内任何登录都会 429。
> 所以它把需要登录的检查全排在限速之前；跑完别立刻再跑第二次（会全程 401）。limiter 在内存里，重启 dsh 即清空。

**公网链路（Quick Tunnel，不碰控制台配置的可复现版本）**：`bash scripts/e2e-public.sh` → **全部通过**。它用 Cloudflare **Quick Tunnel**（不需要账号、不需要域名）起一条真实的 Cloudflare 边缘链路，跑完自动拆掉：

```
公网入口: https://<随机>.trycloudflare.com
边缘可达 ✓
  Android 网络层打公网 HTTPS/WSS（含真实 TLS）  → 15/15 通过
  公网端到端验收（95 项）                       → 95/95 通过
公网链路全部通过 ✓      然后自动关闭公网入口
```

也就是「公网 HTTPS/WSS → Cloudflare 边缘 → cloudflared → 插件网关 → dsh」这条链已经**真实跑通并验收**，包含：

- **`wss://` 上的 WebSocket 101** 与真实数据帧往返（`$events` → `ready` 帧）
- **真实 TLS**：Android 网络层的 SSLSocket + SNI + 证书校验全部实际执行过（原先标注为「未验证」）
- 经公网跑**真实 dsh RPC**（`session/list` 返回 `result.ok=true`）与真实模型**流式输出**
- 公网下的登录 / 限速 / 吊销 / 文件上传下载 / Range 断点续传

**Named Tunnel**：cloudflared 2026.9.3 已安装并**真实连上 Cloudflare 边缘**（QUIC，hkg01/hkg13 两个连接 registered）。你的 `www.jbt99.site` 目前指向另一个项目，所以**没有**把它切到网关 —— 切换只需在控制台把该 Public Hostname 的 Service 改成 `http://127.0.0.1:3081`，改完即用（这条链路本身已被上面的 Quick Tunnel 验证过）。

### 未验证 / 明确不支持

- **Android App 的界面从未在真机或模拟器上运行过** —— 本机既没有 Android 设备也没有模拟器，没有一屏被真实渲染过。但 **UI 层现在已在 JVM 上被真实执行**：`android/` 增加了 Gradle 构建 + Robolectric 测试（`./gradlew :app:testDebugUnitTest` → **90/90 通过**），四个 Activity 走真实生命周期、真实 `performClick()` 点击派发、真实 `net/` 网络栈（打到本地 loopback 测试替身），列表行会真的 measure/layout 后回读文本，下载会真的落盘，分块上传会真的按块发请求。仍未覆盖：真实渲染像素、真实触摸管线、动画/时序、以及 Robolectric 以 shadow 代替的那部分框架行为（含**没有 AndroidKeyStore 实现**，所以真机加密路径未跑）—— 逐条列在 `android/README.md` §5 与 §8。
- ~~**Cloudflare Named Tunnel 的 Public Hostname 尚未指向本网关**~~ **已完成并验证** —— 正式域名 `remote.jbt99.site` → `http://192.168.1.182:3081` 已配置，`bash scripts/verify-public-named.sh` 打真实域名跑通 **95/95**（端到端）与 **14/14**（Android 网络层，含真实 TLS 与 `wss://` 上的 WebSocket 真实帧往返）。
- ~~分块上传模式 B 在 App 侧未实现~~ **已实现**：`GatewayClient.uploadChunked()`，4 MiB 一块（刻意低于网关的 2 GiB `maxUploadBytes` 与反向代理的 64 MiB 可重放体积），8 MiB 以上自动走分块，状态栏显示 `n/N chunks`。有 6 项专门测试 + UI 级分块测试。
- ~~下载走 App 私有目录~~ **已改为公共 Downloads**：API 29+ 写 MediaStore（`IS_PENDING` 先隐藏、成功后发布、失败即删行，不留半成品），26–28 保留原私有目录回退。Range 续传仍然有效（先下到 `.part-` 暂存文件再拷入）。
- ~~token 明文存 SharedPreferences~~ **已改为 AndroidKeyStore AES/GCM 加密**（别名 `dsh_remote_token_v1`，写入后回读校验），旧明文 token 自动迁移，Keystore 不可用时**显式**回退到明文并记录原因（不静默、不丢会话）。
- Android 侧仍未验证：`session/cancel`；真机上的 Keystore 加密路径（Robolectric 无 AndroidKeyStore 实现）。
- **真正的后台推送（进程被杀/重启后仍能收到）未实现** —— 已实现的是**前台服务 + 本地通知**：App 退到后台、息屏、Activity 销毁但进程存活时，`GatewayWatchService` 保持 mux 连接，在**审批请求**与**回合结束**时发通知（点通知直达对应会话，正在看的会话不打扰）。覆盖不到的：force-stop、重启后未打开过 App、被 ROM 杀服务、Doze 延迟。真推送需要外部依赖，没有任何假装实现的代码 —— 接 FCM 需要你提供 Firebase 项目 + `google-services.json`（外加网关侧注册设备 token 的路由与发送调用）；替代方案是自建 ntfy / UnifiedPush。清单见 `android/README.md` §8。
- 手机浏览器观感、中文文件名在各类 ROM 下载器里的表现未验证。

---

## 安全说明

- dsh 只监听回环（CLI 硬拒绝 `--host 0.0.0.0`）。网关监听回环或**具体的内网 IP**（默认 `192.168.1.182`），绝不监听 `0.0.0.0`；公网唯一入口是 Cloudflare 隧道。
- 未设置密码时网关**拒绝一切**（fail-closed），而不是悄悄放行。
- 会话 token 是 HMAC-SHA256 签名的自包含凭据，密钥 32 字节随机、存 600 权限文件。
- `revoke-all` 用「吊销时间戳（epoch）」实现 O(1) 全量吊销：`iat <= epoch` 的 token 一律拒绝，跨重启持久化。
- 登录限速只统计**失败**尝试，按 `X-Forwarded-For` **最右侧**（Cloudflare 追加的那个）计 —— 最左侧可被客户端伪造，信它等于把限速交给攻击者绕过。

  **实测证据**（公网跑 `e2e-verify.sh` 时发现的）：经 Cloudflare 时，客户端自带 `X-Forwarded-For: 203.0.113.77` **无法**造出独立的限速桶——11 次伪造来源的失败尝试仍然打满了真实客户端 IP 的桶。这正是要的行为：Cloudflare 在右侧追加真实客户端 IP，网关取最右侧，于是**伪造 XFF 不能绕过登录限速**。作为对照，直连（前面没有可信代理）时不同来源互不影响。

  由此得到一条**部署与测试的约束**：限速小节会打满真实 IP 的桶，之后任何登录都会 429。所以 `verify-all.sh` / `e2e-public.sh` 里「需要登录的检查」一律排在限速小节之前。验证脚本用 `BEHIND_TRUSTED_PROXY=1` 区分两种拓扑，并各自断言对应的安全属性。
- 下载强制 `Content-Disposition: attachment`，并对 HTML/SVG/JS 也强制附件，避免被写进工作区的恶意页面在同源下代用户发请求。
- 审计日志是只追加 JSONL，记录登录成败、限速触发、认证拒绝、文件读写与越界尝试。

## 已知限制

- 网关的 `/fs/*` 用的是 `node:fs` + 严格双重路径守卫，而不是 dsh 的沙箱服务。它能读的范围与 dsh 进程自身权限相同，但被限制在 `workspaceRoot` 之内。若要更严的隔离，把 `workspaceRoot` 指向一个专用目录。
- multipart 上传会先在 `stateDir` 暂存再复制到目标目录（因为 `path` 字段与 `file` 字段顺序不定），大文件因此多一次拷贝。大文件建议用分块模式（直接落在目标目录）。
- 反向代理的重放只对 ≤ `replayBodyLimitBytes`（默认 64 MiB）的请求体生效；更大的流式上传在 dsh 重启后会收到 `502 upstream_reauth_needed`，需要客户端重试一次（此时会话已刷新，重试即成功）。
- 网关绑定内网 IP 后，该网段内的其它设备可以访问登录页。认证层仍然生效（未登录一律 401/302），但登录页本身会暴露在局域网中。不需要局域网访问时把 `gatewayHost` 改回 `127.0.0.1`，隧道照样能连（cloudflared 与本机同机）。
