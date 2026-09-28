#!/usr/bin/env bash
# e2e-run.sh —— 一条命令跑完整链路验收。
#
#   1. 编译插件
#   2. 搭测试 DSH_HOME + 测试工作区（不动用户正在使用的 web profile）
#   3. 后台启动真实 dsh（带本插件），等端口就绪
#   4. 用 CLI 设置网关密码（进程内 mtime 感知，无需重启）
#   5. 跑 scripts/e2e-verify.sh 的全部验收项
#   6. 收尾：杀掉 dsh，返回验收退出码
set -uo pipefail

ROOT=/root/project/remote-use
DSH_PORT="${DSH_PORT:-3091}"
GW_PORT="${GW_PORT:-3092}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh.log"

echo "═══ 1/5 编译插件 ═══"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) || exit 1

echo
echo "═══ 2/5 搭建测试环境 ═══"
DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || exit 1
echo "ok"

echo
echo "═══ 3/5 启动 dsh（端口 $DSH_PORT，网关 $GW_PORT）═══"
cd "$ROOT/.e2e/workspace"
DSH_HOME="$ROOT/.e2e/dshhome" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!
echo "dsh pid=$DSH_PID，日志 $LOG"

cleanup() {
  if kill -0 "$DSH_PID" 2>/dev/null; then
    kill -TERM "$DSH_PID" 2>/dev/null
    sleep 1
    kill -KILL "$DSH_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

for i in $(seq 1 40); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:$GW_PORT/gateway/health"; then break; fi
  if ! kill -0 "$DSH_PID" 2>/dev/null; then
    echo "dsh 提前退出，日志末尾："; tail -30 "$LOG"; exit 1
  fi
  sleep 1
done
if ! curl -s -m 2 -o /dev/null "http://127.0.0.1:$GW_PORT/gateway/health"; then
  echo "网关未在 40s 内就绪，日志末尾："; tail -30 "$LOG"; exit 1
fi
echo "网关就绪"

echo
echo "═══ 4/5 设置网关密码（验证 CLI + 免重启生效）═══"
( cd "$ROOT/dsh-remote-gateway" && DSH_GATEWAY_PASSWORD="$PW" node bin/cli.mjs set-password admin \
    --state-dir "$ROOT/.e2e/dshhome/dsh-remote-gateway" ) || exit 1

echo
echo "═══ 5/5 端到端验收 ═══"
PW="$PW" B="http://127.0.0.1:$GW_PORT" bash "$ROOT/scripts/e2e-verify.sh"
STATUS=$?
echo
echo "dsh 日志（网关相关行）："
grep 'dsh-remote-gateway' "$LOG" | tail -12 || true
exit "$STATUS"
