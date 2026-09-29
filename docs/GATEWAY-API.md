# dsh-remote-gateway 网关 HTTP 契约（冻结版 v1）

> 本文件是**插件与 Android App 之间唯一的接口契约**。两侧实现都必须严格按本文件。
> 所有事实均来自对已安装 dsh `0.1.5-rc.3` 源码的实测，不是推测。
> 本契约描述的是**本插件自己的** HTTP 接口，与 dsh 版本无关；已在 dsh `0.1.7-rc.2` 上
> 复跑全部验收（95/95 通过），契约未变。

## 0. 拓扑

```
Android App ──HTTPS/WSS──▶ Cloudflare 边缘 ──Tunnel──▶ 网关 127.0.0.1:3081
                                                          │  ← 本契约作用域
                                                          │  认证门禁（本插件实现）
                                                          ├── /gateway/*  认证
                                                          ├── /fs/*       工作区文件
                                                          └── 其他一切 ──反向代理──▶ dsh 127.0.0.1:3080
```

**不变量**
- dsh 自身永远只监听 `127.0.0.1:3080`；网关永远只监听 `127.0.0.1:3081`。
- 隧道 ingress **只**指向 3081，绝不指向 3080。
- 网关的认证检查在**所有**文件路由与代理路由之前执行。

---

## 1. 认证

### 1.1 凭据形态

登录成功后服务端签发**同一个 token**（HMAC-SHA256 签名），可用两种方式携带：

| 方式 | 用法 | 用途 |
|---|---|---|
| Cookie | `drg_session=<token>`；属性 `Path=/; HttpOnly; SameSite=Strict; Max-Age=<n>; Secure` | 浏览器 |
| Bearer | `Authorization: Bearer <token>` | Android App、WebSocket 握手 |

token 结构：`v1.<base64url(JSON payload)>.<base64url(HMAC-SHA256(secret, body))>`
payload：`{ "sub": "<username>", "iat": <ms>, "exp": <ms>, "jti": "<hex16>" }`

### 1.2 路由

#### `POST /gateway/login`
请求：`Content-Type: application/json`，body `{ "username": "admin", "password": "..." }`

响应：
- `200` — `{ "ok": true, "token": "<token>", "username": "admin", "expiresAt": 1234567890123 }`
  并带 `Set-Cookie: drg_session=<token>; ...`
- `400` — body 不是合法 JSON 或缺字段 → `{ "ok": false, "error": "bad_request", "message": "..." }`
- `401` — 用户名或密码错误 → `{ "ok": false, "error": "invalid_credentials" }`
- `429` — 触发限速 → `{ "ok": false, "error": "rate_limited", "retryAfterMs": <ms> }` + `Retry-After` 头
- `503` — 服务端未配置密码 → `{ "ok": false, "error": "not_configured" }`

**安全要求**：用户名不存在与密码错误必须返回**完全相同**的响应体；密码比对用 `timingSafeEqual`；用户不存在时也要做一次等价 scrypt 计算（防用户名枚举计时攻击）。

#### `POST /gateway/logout`
- `200` — `{ "ok": true, "revoked": <bool> }`，并 `Set-Cookie: drg_session=; Max-Age=0`

**登出会真的吊销当前这一个 token**（不只是清 cookie）。Bearer token 是无状态的，
如果只清 cookie，客户端手里那份仍然有效 —— 那样 token 一旦泄露，「登出」就形同虚设。
实现上是维护一张 jti 拒绝表，条目在 token 自身过期后自动清理，因此表大小有上界。

#### `GET /gateway/health`
无需认证。
- `200` — `{ "ok": true, "service": "dsh-remote-gateway", "version": "1.0.0", "configured": <bool>, "uptimeMs": <ms> }`
  `configured=false` 表示未设置密码，此时除本路由外**全部**返回 503。

#### `GET /gateway/session`
需认证。用于 App 冷启动时校验已存 token 是否仍有效。
- `200` — `{ "ok": true, "username": "admin", "expiresAt": <ms> }`
- `401` — token 无效/过期/被吊销

#### `POST /gateway/revoke-all`  （阶段四）
需认证。吊销该用户**全部**已签发 token（含调用者自己）。
- `200` — `{ "ok": true, "revoked": <count> }`

#### `GET /login`
无需认证。返回一个极简登录页 HTML（内联 CSS + 一段内联 JS 调 `POST /gateway/login`）。供手机浏览器兜底使用。

### 1.3 限速

- 作用域：登录接口，按**客户端 IP** 计。
- 规则：15 分钟滑动窗口内最多 10 次**失败**尝试；第 11 次起返回 429。
- 登录成功清零该 IP 的失败计数。
- 客户端 IP 取自 `X-Forwarded-For` **最右侧**（Cloudflare 追加的那个），回退到 socket 地址。绝不信任最左侧（可伪造）。

### 1.4 未认证时的响应

| 请求 | 响应 |
|---|---|
| `Accept` 含 `text/html` 且路径不以 `/api`、`/fs`、`/gateway` 开头 | `302` → `/login` |
| 其他（含 `/api/*`、`/fs/*`、WS 升级） | `401` + `{ "ok": false, "error": "unauthorized" }` |

---

## 2. 工作区文件服务 （阶段二）

**认证**：全部需要 §1 的 token。
**路径语义**：`path` 参数是**相对工作区根**的路径，URL 编码。空或 `.` 表示根。
工作区根 = dsh 会话的工作目录，由插件配置 `workspaceRoot` 指定（默认 dsh 进程 cwd）。

### 2.1 `GET /fs/list?path=<rel>`
- `200`：
```json
{
  "ok": true,
  "path": "build/outputs",
  "entries": [
    { "name": "app-debug.apk", "path": "build/outputs/app-debug.apk",
      "type": "file", "size": 1234567, "mtimeMs": 1234567890123, "mime": "application/vnd.android.package-archive" },
    { "name": "res", "path": "build/outputs/res", "type": "directory", "size": 0, "mtimeMs": 1234567890123, "mime": null }
  ]
}
```
条目按 `type`（directory 在前）再按 `name` 升序。`entries` 上限 5000，超出时附 `"truncated": true`。
- `403` — `{ "ok": false, "error": "path_denied" }`（越界）
- `404` — `{ "ok": false, "error": "not_found" }`
- `405` — 非 GET/HEAD

### 2.2 `GET /fs/download?path=<rel>`
- `200` — 文件原始字节。响应头：
  - `Content-Type`（按扩展名映射，未知 → `application/octet-stream`）
  - `Content-Length`
  - `Content-Disposition: attachment; filename="<name>"; filename*=UTF-8''<pct-encoded>`
  - `Accept-Ranges: bytes`
  - `ETag: "<size>-<mtimeMs>"`
- `206` — 带 `Range: bytes=<start>-<end>` 时。响应头含 `Content-Range: bytes <s>-<e>/<total>`、`Content-Length: <e-s+1>`。
  - 不合法 Range → `416` + `Content-Range: bytes */<total>`
- `403` / `404` / `405` 同上。

### 2.3 `POST /fs/upload`
三种模式，分派顺序：`Content-Type` 以 `multipart/form-data` 开头 → 模式 A；
带 `X-Upload-Id` 头 → 模式 B；否则 → 模式 C。

**模式 A — 单次 multipart**：`Content-Type: multipart/form-data; boundary=...`
- 字段 `path`（可选）：目标相对路径**或目标目录**。缺省 = 文件名落在工作区根。
- 字段 `file`：文件内容。字段顺序不限（`path` 在 `file` 之后也能正确落盘）。
- 可选头 `X-File-SHA256: <hex>`：提供则服务端写入前校验，不匹配返回 400 `{ "error": "checksum_mismatch" }`。

**模式 B — 分块**：`Content-Type: application/octet-stream`
必填头：
- `X-Upload-Id: <string>` — 同一次上传的所有分块共用
- `X-Chunk-Index: <int>` — 从 0 开始
- `X-Total-Chunks: <int>`
- `X-File-Path: <url-encoded rel path>` — 最终目标路径（可为已存在目录）
- 可选 `X-File-Name: <name>` — 当上面的路径是目录时用作文件名
- 可选 `X-File-SHA256: <hex>` — 全文件哈希，任意一块提供即可，服务端在合并后校验

**模式 C — 原始体**：`POST /fs/upload?path=<rel>`，`Content-Type` 为任意非 multipart 类型
（推荐 `application/octet-stream`），**整个请求体就是文件内容**。最简单，适合 App 一次性上传。
- 可选头 `X-File-Name: <name>` — 当 `path` 指向目录（含 `.`）时用作文件名
- 可选头 `X-File-SHA256: <hex>`

**响应**
- 模式 C 与模式 A：`200` `{ "ok": true, "completed": true, "path": "<rel>", "size": <bytes>, "sha256": "<hex>" }`
- 模式 B：`200` `{ "ok": true, "completed": <bool>, "receivedChunks": <n>, "totalChunks": <n> }`；
  `completed=true` 时另含 `{ "path", "size", "sha256" }`

**错误**：`403 path_denied`、`404 not_found`（父目录不存在）、`413 payload_too_large`、
`400 checksum_mismatch`、`400 bad_request`。

**共通要求**
- **目标为已存在目录时（含 `path=.`），文件名取上传文件自身的 basename。**
  客户端提供的 `X-File-Name` / multipart 的 `filename` 会先经 `basename()` 处理，
  因此 `../../evil.txt` 这类值只会落成 `evil.txt`，无法借文件名逃逸。
- 原子写入：先写临时文件，全部就绪后 `fs.rename` 覆盖目标。
- 所有分块收齐前，目标路径**不得**出现半成品。
- 单请求体上限 `maxUploadBytes`（默认 2 GiB）；超出 → `413`。
- 分块会话超过 `chunkSessionTtlMs`（默认 24h）未活动即清理临时文件。

### 2.4 路径越界防护（双重）
1. `path.resolve(workspaceRoot, rel)` 后必须仍以 `workspaceRoot + sep` 为前缀 → 挡 `..` 与编码穿越。
2. 对**已存在**的路径与**其父目录**做 `fs.realpath`，再次检查前缀 → 挡软链逃逸。
3. 拒绝：绝对路径、含 NUL 字节、任何一段为 `..`。
两道检查任一失败即 `403 path_denied`。写操作额外拒绝把目标建到工作区外。

---

## 3. 反向代理（阶段一核心）

除 `/gateway/*`、`/fs/*`、`/login` 外的**所有**路径与方法，认证通过后原样转发到 `http://127.0.0.1:<dshPort>`。

### 3.1 必须的头改写

| 头 | 处理 |
|---|---|
| `Host` | **强制改写为 `127.0.0.1:<dshPort>`** |
| `Cookie` | **丢弃客户端全部 cookie**，只注入网关自己换到的 dsh 会话 cookie |
| `Origin` | **删除**（dsh 允许 Origin 缺失；保留会因与 Host 不同源被 403） |
| `Referer` | 删除 |
| `X-Forwarded-For` / `X-Forwarded-Proto` / `X-Forwarded-Host` | 追加，供 dsh 侧日志 |
| 其余 | 原样透传（含 `Content-Type`、`Authorization` 不转发给 dsh） |

### 3.2 dsh 会话 cookie 的获取（关键）

dsh 的 `/api` 有两道门：Host 信任栅栏(403) → 浏览器会话认证(401)。其 cookie 是
**authority 绑定的**：cookie 名 = `dsh-auth-` + base64url(sha256(`"127.0.0.1:<dshPort>"`))，
签名 payload 内 `authority` 必须等于该 Host 字符串。（0.1.5-rc.3 与 0.1.7-rc.2 实测一致。）

插件作为**进程内**插件，用公开 API 拿 launch token：

```js
const url = ctx.connection.authenticatedUrl(`http://127.0.0.1:${dshPort}/`)
// → "http://127.0.0.1:3080/?token=<processLaunchToken>"
```

然后：
```http
GET /?token=<launchToken> HTTP/1.1
Host: 127.0.0.1:3080
```
→ `303` + `Set-Cookie: dsh-auth-<...>=v1.<...>.<...>`。
**不要跟随重定向**，直接抓 `set-cookie`。缓存该 cookie，注入到后续每个转发请求。

**自愈**：若上游返回 401，说明 dsh 进程重启过（launch token 与签名密钥已变），此时清空缓存、重新兑换一次、重试该请求一次。

### 3.2.1 重放与请求体上限（`replayBodyLimitBytes`，默认 64 MiB）

重放要求请求体还能再发一遍，而**流一旦被消费就没了** —— 所以只有被缓冲下来的请求体才可重放：

| 请求体 | 转发方式 | 上游 401 时的行为 |
|---|---|---|
| ≤ `replayBodyLimitBytes` | 读进内存（可重放） | 换 cookie → **重放一次**，带**完整请求体**，客户端无感 |
| > `replayBodyLimitBytes` | **流式转发** | **不重放**（体已消费）。换新 cookie，返回 `502 upstream_reauth_needed`，客户端重传一次即成功 |

**超过阈值不影响上传能否成功** —— 它只影响能否自动重放。真正决定上传上限的是 `maxUploadBytes`。

流式（不可重放）请求在出发前会先做一次 cookie 新鲜度检查（30s 阈值），把「带着陈旧 cookie 上路然后吃 401」的概率降到最低。

> ⚠️ **实现约束（踩过的坑）**：绝不能在流式路径上做「重放」。流已被 `pipe` 消费完，再 pipe 一次会向上游发出一个**空体**，上游收到残缺请求 —— 比直接报错更糟。`tests/proxy-replay.test.mjs` 锁死了这条：把该判断回退，测试会以「上游收到了 2 次请求」失败。

### 3.3 WebSocket 透传

`/api/remote.mux` 的升级请求走同一认证门禁，然后**逐字节**代理升级：
- 向 `127.0.0.1:<dshPort>` 发起带 `Connection: Upgrade`、`Upgrade: websocket`、`Sec-WebSocket-Key`、`Sec-WebSocket-Version` 的 `http.request`。
- 上游 `upgrade` 事件到达后，把上游的 101 响应头写回客户端 socket，然后双向 `pipe`。
- 上游返回非 101 时，把状态行与头转发给客户端并关闭。
- 除 `Host`/`Cookie`/`Origin` 按 §3.1 改写外，升级头原样透传。

---

## 4. 错误响应统一形状

JSON 错误一律：`{ "ok": false, "error": "<snake_case>", "message": "<人类可读>" }`
`error` 取值集合：`bad_request` `invalid_credentials` `unauthorized` `rate_limited`
`not_configured` `path_denied` `not_found` `method_not_allowed` `payload_too_large`
`checksum_mismatch` `upstream_error` `internal`

## 5. 配置项（插件）

| 键 | 默认 | 说明 |
|---|---|---|
| `gatewayHost` | `127.0.0.1` | 网关绑定地址，**不要改** |
| `gatewayPort` | `3081` | 网关监听端口 |
| `dshPort` | `0` | 0 = 自动取 `ctx.webServer.port` |
| `publicAuthority` | `''` | 公网域名:端口，仅用于生成给用户展示的回调地址与日志 |
| `workspaceRoot` | `''` | 空 = dsh 进程 cwd |
| `adminPassword` | `''` | 明文密码，仅用于首次引导；**不推荐**，优先用 CLI/环境变量 |
| `sessionMaxAgeDays` | `30` | token 有效期 |
| `cookieSecure` | `true` | 生产置 true；本地 http 调试置 false |
| `maxUploadBytes` | `2147483648` | 单请求体上限 |
| `loginMaxAttempts` | `10` | 限速阈值 |
| `loginWindowMs` | `900000` | 限速窗口 |
| `stateDir` | `$DSH_HOME/dsh-remote-gateway` | 密钥/用户/审计日志目录 |
| `auditLog` | `true` | 是否记录审计日志 |
