#!/usr/bin/env bash
# verify-lan-bind.sh —— 验证**生产拓扑**的绑定：网关绑在本机内网 IP 上，dsh 仍只在回环。
#
# 为什么单独验：这是本项目唯一一处「服务在回环之外可达」的地方，必须证明
#   1. 网关确实在**内网 IP** 上服务（隧道 ingress 要指向它）；
#   2. 网关**没有**绑到 0.0.0.0（绑具体 IP 时，127.0.0.1 上应该连不上）；
#   3. dsh 自身**仍然只在回环**（内网 IP 上连 dsh 必须失败）；
#   4. 换了绑定地址，认证层照样生效（未登录仍然 401/302，不是敞开的）。
set -uo pipefail

ROOT=/root/project/remote-use
DSH_PORT="${DSH_PORT:-3093}"
GW_PORT="${GW_PORT:-3094}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh-lan.log"

# e2e-setup.sh 会 rm -rf .e2e；cwd 钉在不会被删的地方，避免子进程刷 getcwd 报错。
cd "$ROOT"

# 自动取本机第一个非回环、非 docker、非 Meta 的 IPv4
LAN_IP="${LAN_IP:-$(ip -4 addr show 2>/dev/null \
  | awk '/inet /{print $2}' | cut -d/ -f1 \
  | grep -vE '^127\.|^172\.17\.|^198\.18\.' | head -1)}"

if [ -z "$LAN_IP" ]; then
  echo "找不到内网 IP，跳过"; exit 0
fi

PASS=0
FAIL=0
FAILED_NAMES=()
ok()  { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad() { echo "  ✗ $1"; FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); }

DSH_PID=""
cleanup() {
  if [ -n "$DSH_PID" ] && kill -0 "$DSH_PID" 2>/dev/null; then
    kill -TERM "$DSH_PID" 2>/dev/null; sleep 1; kill -KILL "$DSH_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

echo "内网 IP: $LAN_IP     dsh: 127.0.0.1:$DSH_PORT     网关: $LAN_IP:$GW_PORT"

echo
echo "── 搭建并启动 ──"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) >/dev/null || { echo "编译失败"; exit 1; }
GW_HOST="$LAN_IP" DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" \
  bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || { echo "环境搭建失败"; exit 1; }

cd "$ROOT/.e2e/workspace"
DSH_HOME="$ROOT/.e2e/dshhome" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!

ready=0
for _ in $(seq 1 45); do
  if curl -s -m 2 -o /dev/null "http://$LAN_IP:$GW_PORT/gateway/health"; then ready=1; break; fi
  kill -0 "$DSH_PID" 2>/dev/null || { echo "dsh 提前退出："; tail -25 "$LOG"; exit 1; }
  sleep 1
done
[ "$ready" = 1 ] || { echo "网关未在 $LAN_IP:$GW_PORT 上就绪："; tail -25 "$LOG"; exit 1; }
ok "网关已在内网 IP $LAN_IP:$GW_PORT 上就绪"

( cd "$ROOT/dsh-remote-gateway" && DSH_GATEWAY_PASSWORD="$PW" node bin/cli.mjs set-password admin \
    --state-dir "$ROOT/.e2e/dshhome/dsh-remote-gateway" ) >/dev/null

# curl 直连判定：--noproxy '*' 很关键 —— 本机有代理环境时，
# 访问内网 IP 也可能被代理接管，那样测的就不是本机监听了。
direct() { curl -s --noproxy '*' -m 5 -o /dev/null -w '%{http_code}' "$@" 2>/dev/null; }

echo
echo "── 1. 网关在内网 IP 上服务 ──"
check_code="$(direct "http://$LAN_IP:$GW_PORT/gateway/health")"
if [ "$check_code" = "200" ]; then ok "GET http://$LAN_IP:$GW_PORT/gateway/health -> 200"; else bad "内网 IP 上健康检查返回 $check_code"; fi

echo
echo "── 2. 绑定的是具体 IP，不是 0.0.0.0 ──"
# 绑具体 IP 时，回环上不应该有监听 —— 这正是「只开了内网一个口子」的证据。
loop_code="$(direct "http://127.0.0.1:$GW_PORT/gateway/health")"
if [ "$loop_code" = "000" ] || [ -z "$loop_code" ]; then
  ok "127.0.0.1:$GW_PORT 无监听（000）—— 证明没绑 0.0.0.0"
else
  bad "127.0.0.1:$GW_PORT 竟然可达（$loop_code），说明绑的是 0.0.0.0 而不是具体 IP"
fi

echo
echo "── 3. dsh 自身仍然只在回环 ──"
dsh_lan="$(direct "http://$LAN_IP:$DSH_PORT/")"
if [ "$dsh_lan" = "000" ] || [ -z "$dsh_lan" ]; then
  ok "dsh 在内网 IP 上不可达（000）—— dsh 没有被暴露"
else
  bad "dsh 在内网 IP $DSH_PORT 上可达（$dsh_lan），这是严重问题"
fi
dsh_loop="$(direct "http://127.0.0.1:$DSH_PORT/")"
if [ "$dsh_loop" = "401" ] || [ "$dsh_loop" = "302" ] || [ "$dsh_loop" = "303" ]; then
  ok "dsh 在回环上仍在（$dsh_loop，需认证）"
else
  bad "dsh 回环响应异常：$dsh_loop"
fi

echo
echo "── 4. 换了绑定地址，认证层照样生效 ──"
api_code="$(direct "http://$LAN_IP:$GW_PORT/api/session/list")"
if [ "$api_code" = "401" ]; then ok "未登录访问内网 IP 的 /api/* -> 401"; else bad "未登录 /api/* 返回 $api_code，期望 401"; fi
fs_code="$(direct "http://$LAN_IP:$GW_PORT/fs/list?path=.")"
if [ "$fs_code" = "401" ]; then ok "未登录访问内网 IP 的 /fs/list -> 401"; else bad "未登录 /fs/list 返回 $fs_code，期望 401"; fi
html_code="$(direct -H 'Accept: text/html' "http://$LAN_IP:$GW_PORT/")"
if [ "$html_code" = "302" ]; then ok "未登录浏览器请求 -> 302 到 /login"; else bad "浏览器请求返回 $html_code，期望 302"; fi

echo
echo "── 5. 内网 IP 上登录后功能正常 ──"
TOKEN="$(curl -s --noproxy '*' -m 10 -X POST -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" "http://$LAN_IP:$GW_PORT/gateway/login" \
  | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{process.stdout.write(JSON.parse(s).token||"")}catch{process.stdout.write("")}})')"
if [ -n "$TOKEN" ]; then ok "经内网 IP 登录拿到 token"; else bad "经内网 IP 登录失败"; fi
if [ -n "$TOKEN" ]; then
  rpc_code="$(direct -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
    -d '{"type":"client-request","rpcId":"lan-1","method":"session/list","payload":{"args":{"_request":{}}}}' \
    "http://$LAN_IP:$GW_PORT/api/session/list")"
  if [ "$rpc_code" = "200" ]; then ok "经内网 IP 走反向代理到 dsh -> 200"; else bad "经内网 IP 的 RPC 返回 $rpc_code"; fi
fi

echo
echo "════════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  echo "  内网绑定验证全部通过 ✓   通过: $PASS  失败: $FAIL"
  echo "  隧道 Public Hostname 应填：  http://$LAN_IP:$GW_PORT"
  echo "════════════════════════════════════════════"
  exit 0
fi
echo "  内网绑定验证有失败项：通过 $PASS，失败 $FAIL"
for n in "${FAILED_NAMES[@]}"; do echo "    - $n"; done
echo "════════════════════════════════════════════"
exit 1
