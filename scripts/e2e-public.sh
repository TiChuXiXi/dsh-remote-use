#!/usr/bin/env bash
# e2e-public.sh —— 用 Cloudflare **Quick Tunnel** 验证完整的公网链路：
#
#   公网 HTTPS/WSS  →  Cloudflare 边缘  →  cloudflared（本机）  →  插件网关  →  dsh
#
# 为什么用 Quick Tunnel 而不是用户的 Named Tunnel：
#   Quick Tunnel 不需要账号、不需要域名、不碰用户在控制台里的任何配置，
#   拿到的是一条真实的 Cloudflare 边缘链路（同样的 TLS、同样的协议翻译、
#   同样的 WebSocket 处理），因此能在不动用户生产隧道的前提下就验收
#   「公网 WebSocket 101」这条硬指标。
#
# 退出时会**自动拆掉**公网隧道 —— 不要把一台 dsh 网关留在公网上无人看管。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DSH_PORT="${DSH_PORT:-3091}"
GW_PORT="${GW_PORT:-3092}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh.log"
CFLOG="$ROOT/.e2e/quick.log"

DSH_PID=""
CF_PID=""

cleanup() {
  echo
  echo "─── 拆掉公网隧道与测试实例 ───"
  [ -n "$CF_PID" ] && kill -TERM "$CF_PID" 2>/dev/null
  [ -n "$DSH_PID" ] && { kill -TERM "$DSH_PID" 2>/dev/null; sleep 1; kill -KILL "$DSH_PID" 2>/dev/null; }
  echo "公网入口已关闭。"
}
trap cleanup EXIT

echo "═══ 1/5 准备测试环境（cookieSecure=true，与生产默认一致）═══"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) >/dev/null || { echo "编译失败"; exit 1; }
COOKIE_SECURE=true DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || exit 1

echo
echo "═══ 2/5 启动 dsh + 插件网关（均只监听回环）═══"
cd "$ROOT/.e2e/workspace"
DSH_HOME="$ROOT/.e2e/dshhome" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!
for _ in $(seq 1 45); do
  curl -s -m 2 -o /dev/null "http://127.0.0.1:$GW_PORT/gateway/health" && break
  kill -0 "$DSH_PID" 2>/dev/null || { echo "dsh 提前退出："; tail -25 "$LOG"; exit 1; }
  sleep 1
done
curl -s -m 2 -o /dev/null "http://127.0.0.1:$GW_PORT/gateway/health" || { echo "网关未就绪"; tail -25 "$LOG"; exit 1; }
( cd "$ROOT/dsh-remote-gateway" && DSH_GATEWAY_PASSWORD="$PW" node bin/cli.mjs set-password admin \
    --state-dir "$ROOT/.e2e/dshhome/dsh-remote-gateway" ) >/dev/null || exit 1

echo
echo "═══ 3/5 起 Quick Tunnel 暴露网关 ═══"
# 本机 DNS 不返回 SRV 记录，cloudflared 的边缘发现会失败；显式给出边缘地址绕过它。
cd "$ROOT/.e2e"
HOME="$ROOT/.e2e/cfhome" \
TUNNEL_EDGE=region1.v2.argotunnel.com:7844,region2.v2.argotunnel.com:7844 \
  setsid nohup cloudflared tunnel --url "http://127.0.0.1:$GW_PORT" --no-autoupdate >"$CFLOG" 2>&1 &
CF_PID=$!

PUBLIC=""
for _ in $(seq 1 40); do
  PUBLIC="$(grep -oE 'https://[a-z0-9-]+\.trycloudflare\.com' "$CFLOG" 2>/dev/null | head -1)"
  [ -n "$PUBLIC" ] && break
  sleep 1
done
[ -n "$PUBLIC" ] || { echo "未能取得 Quick Tunnel URL："; tail -20 "$CFLOG"; exit 1; }
echo "公网入口: $PUBLIC"

# 等边缘真正可达（隧道注册需要几秒）
reachable=0
for _ in $(seq 1 30); do
  if [ "$(curl -s -m 10 -o /dev/null -w '%{http_code}' "$PUBLIC/gateway/health")" = "200" ]; then reachable=1; break; fi
  sleep 2
done
[ "$reachable" = 1 ] || { echo "公网入口 30 次尝试后仍不可达"; exit 1; }
echo "边缘可达 ✓"

echo
echo "═══ 4/5 Android 网络层打公网 HTTPS/WSS（含真实 TLS）═══"
if [ -f "$ROOT/android/verify/run_verify.sh" ]; then
  ( cd "$ROOT/android" && bash verify/run_verify.sh --url "$PUBLIC" --user admin --pass "$PW" ) | tail -20
  ANDROID=${PIPESTATUS[0]}
else
  echo "跳过：找不到 android/verify/run_verify.sh"
  ANDROID=0
fi

echo "═══ 5/5 公网端到端验收（95 项，BEHIND_TRUSTED_PROXY=1）═══"
B="$PUBLIC" PW="$PW" BEHIND_TRUSTED_PROXY=1 bash "$ROOT/scripts/e2e-verify.sh"
SUITE=$?

echo
echo
if [ "$SUITE" -eq 0 ] && [ "$ANDROID" -eq 0 ]; then
  echo "公网链路全部通过 ✓   （$PUBLIC）"
  exit 0
fi
echo "公网链路存在失败：suite=$SUITE android=$ANDROID"
exit 1
