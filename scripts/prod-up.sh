#!/usr/bin/env bash
# prod-up.sh —— 按**生产拓扑**起 dsh + 网关，供隧道/公网域名使用。
#
#   dsh        127.0.0.1:3080        （只回环，CLI 硬拒绝 0.0.0.0）
#   网关       192.168.1.182:3081    （隧道 ingress 指向这里）
#   cookieSecure = true              （Cloudflare 终结 TLS，浏览器看到的是 HTTPS）
#
# 为什么不直接用 ~/.dsh：本机 `/` 是只读挂载，~/.dsh 写不了。这里把 DSH_HOME 指到
# 工作区，用符号链接借用真实 profile 的 node_modules（读取可以穿越符号链接）。
# 如果你在能写 ~/.dsh 的环境里跑，把 dsh 那行换成正常的 profile 启动即可。
#
# 用法：
#   bash scripts/prod-up.sh            # 前台
#   bash scripts/prod-up.sh --daemon   # 后台（日志 .runtime/dsh-prod.log）
set -uo pipefail

ROOT=/root/project/remote-use
cd "$ROOT"

DSH_PORT="${DSH_PORT:-3080}"
GW_PORT="${GW_PORT:-3081}"
GW_HOST="${GW_HOST:-192.168.1.182}"
WS="${WS:-$ROOT/.runtime/workspace}"
DHOME="${DHOME:-$ROOT/.runtime/dshhome}"
PROFILE="$DHOME/profiles/web"
REAL_HOME="${REAL_HOME:-/root/.dsh}"
LOG="$ROOT/.runtime/dsh-prod.log"

echo "═══ 1/3 编译插件 ═══"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) >/dev/null || { echo "编译失败"; exit 1; }
echo "ok"

echo
echo "═══ 2/3 准备 DSH_HOME（$DHOME）═══"
mkdir -p "$PROFILE/node_modules" "$WS" "$ROOT/.runtime"
ln -sfn "$REAL_HOME/profiles/node_modules" "$DHOME/profiles/node_modules"
[ -f "$REAL_HOME/.credentials.yaml" ] && ln -sfn "$REAL_HOME/.credentials.yaml" "$DHOME/.credentials.yaml"
[ -f "$REAL_HOME/settings.yaml" ] && ln -sfn "$REAL_HOME/settings.yaml" "$DHOME/settings.yaml"
cp "$REAL_HOME/profiles/web/cordis.yml" "$PROFILE/cordis.yml" 2>/dev/null
cp "$REAL_HOME/profiles/web/pnpm-workspace.yaml" "$PROFILE/pnpm-workspace.yaml" 2>/dev/null

cat > "$PROFILE/package.json" <<JSON
{
  "name": "dsh-profile-web-prod",
  "private": true,
  "dsh": {
    "profile": {
      "bundles": [
        "@deepseek-ai/dsh-base",
        "@deepseek-ai/dsh-web-app",
        "dsh-remote-gateway"
      ],
      "patchReload": "live"
    }
  }
}
JSON

ln -sfn "$ROOT/dsh-remote-gateway" "$PROFILE/node_modules/dsh-remote-gateway"

# 生产配置：内网 IP + 3081 + Secure cookie + 2 GiB 上传 + 64 MiB 可重放体积
cat > "$PROFILE/cordis.patch.yml" <<YAML
- id: remote-gateway
  config:
    gatewayHost: $GW_HOST
    gatewayPort: $GW_PORT
    dshPort: 0
    publicAuthority: remote.jbt99.site
    workspaceRoot: $WS
    adminPassword: ''
    sessionMaxAgeDays: 30
    cookieSecure: true
    maxUploadBytes: 2147483648
    replayBodyLimitBytes: 67108864
    loginMaxAttempts: 10
    loginWindowMs: 900000
    auditLog: true
YAML
echo "网关: http://$GW_HOST:$GW_PORT    dsh: 127.0.0.1:$DSH_PORT"
echo "工作区: $WS"

echo
echo "═══ 3/3 启动 dsh ═══"
if [ "${1:-}" = "--daemon" ]; then
  DSH_HOME="$DHOME" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open \
    >"$LOG" 2>&1 </dev/null &
  disown 2>/dev/null || true
  echo "已后台启动，日志：$LOG"
else
  cd "$WS"
  DSH_HOME="$DHOME" dsh --profile web --port "$DSH_PORT" --no-open
fi
