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
| `android/` | Android App（纯 Java，无 Gradle，aapt2+d8+apksigner 流水线） |
| `docs/GATEWAY-API.md` | **冻结的网关 HTTP 契约**，插件与 App 的共同依据 |
| `docs/PLUGIN-NOTES.md` | dsh 0.1.5-rc.3 插件/认证机制实测笔记（含大量被证伪的传言） |
| `docs/dsh-transport-spec.md` | dsh 自身线上协议实测规格（84 个 RPC 端点 + WebSocket mux） |
| `scripts/e2e-run.sh` | 一条命令跑完整链路验收（真实 dsh，不进 mock） |
| `scripts/e2e-verify.sh` | 85 项端到端验收检查 |

---

## 快速开始

### 1. 构建插件

```bash
cd dsh-remote-gateway
npm install
npm run build          # tsc → lib/
npm test               # 61 项单元测试
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
    gatewayPort: 3081        # 网关端口，隧道 ingress 指向它
    dshPort: 0               # 0 = 自动使用 dsh 实际监听端口
    publicAuthority: ''      # 公网域名[:端口]，仅用于日志
    workspaceRoot: ''        # 空 = dsh 进程 cwd
    cookieSecure: true       # 走 Cloudflare（HTTPS）时必须 true
    maxUploadBytes: 2147483648
    loginMaxAttempts: 10     # 15 分钟窗口内失败次数上限
    loginWindowMs: 900000
    auditLog: true
    stateDir: ''             # 空 = $DSH_HOME/dsh-remote-gateway
```

> patch 覆盖会替换**整行** config，所以每个键都要写全。

**注意：`gatewayHost` 无法改成非回环地址** —— 配置层会强制拉回 `127.0.0.1` 并打印告警。这是刻意的安全不变量。

---

## 公网暴露（Cloudflare Named Tunnel）

### ingress 规则

在 Cloudflare Zero Trust 控制台把 Public Hostname 的 Service 指向**网关端口**：

```
www.你的域名  →  HTTP  →  127.0.0.1:3081
```

**绝不要指向 3080。** 指向 3080 会让 `/api` 的流量绕过认证层，审计与限速一并失效。

### 关闭 HTTP/2 与 HTTP/3（重要）

在域名 → Network 设置里关闭 **HTTP/2** 和 **HTTP/3**，强制浏览器与边缘之间走 HTTP/1.1。

原因：HTTP/2 不支持 `Upgrade: websocket`，它用 RFC 8441 的 Extended CONNECT；而 Cloudflare Tunnel 的 HTTP origin service 不会把 Extended CONNECT 翻译成 HTTP/1.1 Upgrade，导致 dsh 的 `/api/remote.mux` 永远建不起来 —— 表现是**界面能开、但发消息没有任何流式输出**。

### 启动隧道

```bash
cloudflared tunnel run --token <你的隧道token>
```

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

dsh 0.1.5 的 `/api` 有两道门：Host/Origin 信任栅栏（403）+ authority 绑定的 HMAC cookie 认证（401）。cookie 名是 `dsh-auth-` + base64url(sha256(authority))，签名载荷里的 `authority` 必须等于 Host。

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

**已真实验证**（`bash scripts/e2e-run.sh` → **95/95 通过**，打的是真实 dsh 0.1.5-rc.3，不是 mock）：

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
- 安全不变量：网关只监听回环；dsh 直连仍需认证；`users.json`/`session.key` 权限 600 且无明文；审计日志五类事件齐全
- 登出**真的吊销该 token**（Bearer 立刻 401，不只是清 cookie）；`revoke-all` 后旧 token 立即失效、新 token 可用；`revoke-all` 后**同一毫秒内重登**的新 token 也可用（竞态回归）
- 安装命令 `dsh plugin --profile web add <插件目录>` 实测生效（写入 `link:` 依赖并在 profile `node_modules` 建链）

**单元测试** `npm test` → **68/68 通过**（路径守卫、token 篡改与吊销、密码策略、Range、限速、MIME、cookie 序列化）。

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

**隧道**：cloudflared 2026.9.3 已安装并**真实连上 Cloudflare 边缘**（QUIC，hkg01/hkg13 两个连接 registered），公网请求 `https://www.jbt99.site/` 已到达边缘并被转发到 ingress 配置的 origin。**但 ingress 当前指向另一台机器 `192.168.1.121:8899`，尚未指向本机网关**，所以「公网 → 网关 → dsh」这最后一跳需要你在控制台把 Service 改成 `http://127.0.0.1:3081` 之后才能验收。

### 未验证 / 明确不支持

- **Android App 的界面（Activity/View/Compose 之外的 UI 层）从未在真机或模拟器上运行过** —— 本机既没有 Android 设备也没有模拟器，没有一屏被渲染过、没有一个按钮被点过。App 的网络层已对真实网关验证，但 UI 层只有「能用 android.jar 作为 bootclasspath 编译通过」这一级证据。
- **公网 ingress 未指向本机网关**，因此「公网 HTTPS/WSS 打到本网关」这一跳未验收（隧道本身已确认连接成功，见上文）。
- Android 侧：`session/cancel` 未验证；**分块上传模式 B 未实现**（只用 multipart 模式 A）；下载保存到 App 私有目录 `getExternalFilesDir`，未走 MediaStore 公共下载目录；token 存在明文 `SharedPreferences`（MODE_PRIVATE），**未用 Keystore 加密**；`wss://`（TLS）代码已写但未实际执行过。
- **推送通知（阶段四 4.2）未实现** —— FCM 需要一个 Firebase 项目与 `google-services.json`，属于外部依赖，没有它无法编译进去。
- 手机浏览器观感、中文文件名在各类 ROM 下载器里的表现未验证。

---

## 安全说明

- dsh 只监听回环；网关也只监听回环。公网唯一入口是 Cloudflare 隧道。
- 未设置密码时网关**拒绝一切**（fail-closed），而不是悄悄放行。
- 会话 token 是 HMAC-SHA256 签名的自包含凭据，密钥 32 字节随机、存 600 权限文件。
- `revoke-all` 用「吊销时间戳（epoch）」实现 O(1) 全量吊销：`iat <= epoch` 的 token 一律拒绝，跨重启持久化。
- 登录限速只统计**失败**尝试，按 `X-Forwarded-For` **最右侧**（Cloudflare 追加的那个）计 —— 最左侧可被客户端伪造，信它等于把限速交给攻击者绕过。
- 下载强制 `Content-Disposition: attachment`，并对 HTML/SVG/JS 也强制附件，避免被写进工作区的恶意页面在同源下代用户发请求。
- 审计日志是只追加 JSONL，记录登录成败、限速触发、认证拒绝、文件读写与越界尝试。

## 已知限制

- 网关的 `/fs/*` 用的是 `node:fs` + 严格双重路径守卫，而不是 dsh 的沙箱服务。它能读的范围与 dsh 进程自身权限相同，但被限制在 `workspaceRoot` 之内。若要更严的隔离，把 `workspaceRoot` 指向一个专用目录。
- multipart 上传会先在 `stateDir` 暂存再复制到目标目录（因为 `path` 字段与 `file` 字段顺序不定），大文件因此多一次拷贝。大文件建议用分块模式（直接落在目标目录）。
- 反向代理的重放只对 ≤ 1 MiB 的请求体生效；更大的流式上传在 dsh 重启后会收到 502，需要客户端重试。
