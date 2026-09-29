#!/usr/bin/env bash
# verify-public-named.sh —— 打**真实 Named Tunnel 公网域名**的完整验收。
#
#   公网 HTTPS/WSS  →  Cloudflare 边缘  →  cloudflared（本机）  →  网关 192.168.1.182:3081  →  dsh
#
# 与 e2e-public.sh 的区别：那个用 Quick Tunnel（临时域名，跑完即拆），
# 这个打你已经配好的正式域名，验证的是**真实生产链路**。
#
# 前置（本脚本会检查，不会替你启动）：
#   bash scripts/prod-up.sh --daemon      # 起 dsh + 网关
#   bash scripts/tunnel-up.sh --daemon    # 起隧道
#
# 用法：
#   DOMAIN=remote.jbt99.site bash scripts/verify-public-named.sh
#
# ⚠ 副作用：套件最后的「登录限速」小节会**故意**打满你真实公网 IP 的失败计数桶，
#   之后约 15 分钟内（loginWindowMs）任何登录都会 429。所以：
#     - 需要登录的检查一律排在限速小节之前（本脚本已按此排序）；
#     - 跑完别马上再跑一次，否则第二次会全程 401（拿不到 token）。
#       limiter 状态在内存里，重启 dsh 即可立刻清空。
set -uo pipefail

ROOT=/root/project/remote-use
cd "$ROOT"

DOMAIN="${DOMAIN:-remote.jbt99.site}"
B="https://$DOMAIN"
USER_NAME="${USER_NAME:-admin}"
PW="${PW:-Str0ngPassw0rd!}"
# 生产拓扑：网关绑内网 IP，dsh 在 3079（3080 常被别的 dsh 占着）
GW_HOST="${GW_HOST:-192.168.1.182}"
GW_PORT="${GW_PORT:-3081}"
DSH_PORT="${DSH_PORT:-3079}"
WS="$ROOT/.runtime/workspace"
STATE="$ROOT/.runtime/dshhome/dsh-remote-gateway"

FAILED=()

echo "══════════════════════════════════════════"
echo "  公网验收：$B"
echo "══════════════════════════════════════════"

echo
echo "── 0. 前置检查 ──"
if ! grep -q "Registered tunnel connection" "$ROOT/.runtime/cf-named.log" 2>/dev/null; then
  echo "隧道没有连上边缘，先跑：bash scripts/tunnel-up.sh --daemon"; exit 1
fi
echo "  ✓ 隧道已注册边缘连接（$(grep -c 'Registered tunnel connection' "$ROOT/.runtime/cf-named.log") 条）"

if ! curl -s --noproxy '*' -m 5 -o /dev/null "http://$GW_HOST:$GW_PORT/gateway/health"; then
  echo "网关没在 $GW_HOST:$GW_PORT，先跑：bash scripts/prod-up.sh --daemon"; exit 1
fi
echo "  ✓ 网关在 $GW_HOST:$GW_PORT"

H="$(curl -s -m 20 -o /dev/null -w '%{http_code}' "$B/gateway/health")"
if [ "$H" = "200" ]; then echo "  ✓ 公网 $B/gateway/health -> 200"; else echo "  ✗ 公网健康检查返回 $H（隧道/ingress 没配好？）"; exit 1; fi

# 样本夹具：/fs/* 那一批用例全靠它。生产工作区默认是空的，不造的话会整片 404，
# 看起来像产品坏了，其实只是没有可读的文件。
if bash "$ROOT/scripts/make-fixtures.sh" "$WS" "$ROOT/.runtime" >/dev/null; then
  echo "  ✓ 样本夹具已就绪（$WS）"
else
  echo "  ✗ 样本夹具创建失败（$WS）—— 后面的 /fs/* 用例会整片失败，先修这个"; exit 1
fi

# ─────────────────────────────────────────── 1. Android 网络层（必须早于限速小节）
echo
echo "══════════════════════════════════════════"
echo "  1. Android 网络层打公网 HTTPS/WSS（含真实 TLS）
echo "══════════════════════════════════════════"
( cd "$ROOT/android" && bash verify/run_verify.sh --url "$B" --user "$USER_NAME" --pass "$PW" --out "$ROOT/.runtime/verify-out" ) \
  || FAILED+=("Android 网络层（公网）")

# ─────────────────────────────────────────── 2. 端到端 95 项
echo
echo "══════════════════════════════════════════"
echo "  2. 公网端到端验收（95 项）
echo "══════════════════════════════════════════"
B="$B" WS="$WS" STATE="$STATE" USER_NAME="$USER_NAME" PASSWORD="$PW" \
  GW_HOST="$GW_HOST" GW_PORT="$GW_PORT" DSH_PORT="$DSH_PORT" \
  BEHIND_TRUSTED_PROXY=1 \
  bash "$ROOT/scripts/e2e-verify.sh" || FAILED+=("公网端到端验收")

echo
echo "══════════════════════════════════════════"
if [ "${#FAILED[@]}" -eq 0 ]; then
  echo "  公网链路全部通过 ✓"
  echo "  入口：$B"
  echo "══════════════════════════════════════════"
  exit 0
fi
echo "  以下步骤失败："
for f in "${FAILED[@]}"; do echo "    - $f"; done
echo "══════════════════════════════════════════"
exit 1
