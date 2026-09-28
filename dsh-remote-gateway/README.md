# dsh-remote-gateway

dsh 插件：**认证网关 + 反向代理 + 工作区文件服务**。

让手机通过公网安全访问家用电脑上的 dsh。dsh 始终只监听 `127.0.0.1`，所有外部流量必须先经过本插件的认证层。

- 完整项目说明、部署与隧道配置见 [上级目录的 README](../README.md)
- dsh 插件 / 认证机制的实测笔记见 [`../docs/PLUGIN-NOTES.md`](../docs/PLUGIN-NOTES.md)
- 网关 HTTP 契约见 [`../docs/GATEWAY-API.md`](../docs/GATEWAY-API.md)

## 能力

| 路由 | 说明 |
|---|---|
| `POST /gateway/login` | 用户名/密码登录，签发 HMAC-SHA256 会话 token（cookie 或 Bearer 均可） |
| `POST /gateway/logout` | 登出，**真的吊销该 token**（不只是清 cookie） |
| `POST /gateway/revoke-all` | 吊销该用户的全部会话 |
| `GET /gateway/session` | 校验当前凭据 |
| `GET /gateway/health` | 健康检查（无需认证） |
| `GET /login` | 极简登录页 |
| `GET /fs/list` | 工作区目录列表 |
| `GET /fs/download` | 文件下载，支持 `Range` / 206 断点续传 |
| `POST /fs/upload` | 上传，三种模式：原始体 / 分块（可续传）/ multipart |
| 其余一切 | 认证后反向代理到 dsh（含 `/api/remote.mux` 的 WebSocket 透传） |

## 安装

```bash
npm install && npm run build
npx dsh-remote-gateway set-password
dsh plugin --profile web add "$PWD"
```

并把 `dsh-remote-gateway` 加进 profile `package.json` 的 `dsh.profile.bundles`。

## 零运行时依赖

插件在运行时**只 import `node:` 内置模块**。ctx 由 cordis 注入，类型在 `src/types.ts` 本地声明。

这么做有三个理由：
1. npm 上公开的 dsh 包版本（`0.0.1-rc.1`）远落后于随 dsh 安装的 `0.1.5-rc.3`，依赖 npm 版本会拿到过时甚至不存在的签名。
2. 插件被 loader 从 profile 目录解析，少一个依赖就少一个解析失败面。
3. 对一个认证网关来说，零依赖同时意味着零供应链攻击面。

配置校验因此不用 Schemastery，而是 `src/config.ts` 里的手写归一化（含范围钳制）。这与 `dsh-git-vcs` 的做法一致（其源码注释同样写明「本插件刻意零运行时依赖」）。

## 开发

```bash
npm run build          # tsc → lib/
npm test               # 68 项单元测试（node --test，零测试依赖）

# 完整链路验收（真实 dsh，不进 mock）
bash ../scripts/e2e-run.sh
```

## 目录

```
src/
  index.ts           插件入口：name / inject / apply，用 ctx.effect 注册生命周期
  config.ts          配置归一化（零依赖，含安全不变量强制）
  types.ts           本地声明的 dsh 宿主 API 面
  http-util.ts       读体（带上限）、JSON 响应、cookie、客户端 IP
  log.ts             审计日志（只追加 JSONL）
  auth/
    password.ts      scrypt 用户库（防用户名枚举计时）
    token.ts         HMAC token + epoch/jti 两种吊销
    ratelimit.ts     滑动窗口限速（内存有界）
    routes.ts        /gateway/* 与 /login，全网关唯一的认证判定点
  proxy/
    dsh-cookie.ts    launch token → dsh 会话 cookie 兑换（单飞 + 自愈）
    gateway.ts       监听、认证门禁、反向代理、WebSocket 透传
  files/
    guard.ts         双重路径越界防护（字符串 + realpath）
    mime.ts          扩展名 → MIME
    routes.ts        列表 / 下载（Range）/ 上传（三种模式，原子落盘）
tests/
  unit.test.mjs      单元测试
  ws-probe.mjs       零依赖 RFC 6455 客户端，验证真实帧往返
```

## 安全要点

- **fail-closed**：未设置密码时除健康检查与登录页外一律 503，不悄悄放行。
- `gatewayHost` 无法改成非回环地址，配置层强制拉回并告警。
- 用户不存在与密码错误返回**完全相同**的响应，且耗时一致（等成本 scrypt）。
- 限速只统计失败尝试，按 `X-Forwarded-For` **最右侧**计（最左侧可伪造）。
- 转发给 dsh 时**丢弃客户端全部 cookie**，只注入网关自己兑换的凭据。
- 下载强制附件，HTML/SVG/JS 亦强制，避免同源主动内容。
- 审计日志覆盖登录成败、限速、认证拒绝、文件读写与越界。
