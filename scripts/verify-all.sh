#!/usr/bin/env bash
# verify-all.sh —— 一条命令跑完全部**可复现**的验证。
#
#   1. 插件编译
#   2. 插件单元测试
#   3. 起一个真实 dsh（测试 DSH_HOME，不碰在用的 profile）+ 插件网关
#   4. 端到端验收（95 项，含真实 WebSocket 帧往返）
#   5. Android APK 构建 + 可安装性自检
#   6. Android 网络层对**真实网关**跑 JVM harness
#   7. 收尾
#
# 环境要求：dsh 已安装；Android 部分的工具链路径见 android/build_apk.sh 顶部。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DSH_PORT="${DSH_PORT:-3091}"
GW_PORT="${GW_PORT:-3092}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh.log"

FAILED=()
step() { echo; echo "══════════════════════════════════════════"; echo "  $1"; echo "══════════════════════════════════════════"; }

# ─────────────────────────────────────────────── 1. 编译 + 2. 单元测试
step "1-2. 插件编译 + 单元测试"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) || { echo "编译失败"; exit 1; }
( cd "$ROOT/dsh-remote-gateway" && node --test tests/unit.test.mjs ) || FAILED+=("单元测试")

# ─────────────────────────────────────────────── 3. 起真实 dsh + 网关
step "3. 搭建测试环境并启动真实 dsh（端口 $DSH_PORT，网关 $GW_PORT）"
DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || { echo "环境搭建失败"; exit 1; }

cd "$ROOT/.e2e/workspace"
DSH_HOME="$ROOT/.e2e/dshhome" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!
cleanup() {
  if kill -0 "$DSH_PID" 2>/dev/null; then
    kill -TERM "$DSH_PID" 2>/dev/null; sleep 1; kill -KILL "$DSH_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

ready=0
for _ in $(seq 1 45); do
  if curl -s -m 2 -o /dev/null "http://127.0.0.1:$GW_PORT/gateway/health"; then ready=1; break; fi
  if ! kill -0 "$DSH_PID" 2>/dev/null; then echo "dsh 提前退出："; tail -25 "$LOG"; exit 1; fi
  sleep 1
done
[ "$ready" = 1 ] || { echo "网关未就绪："; tail -25 "$LOG"; exit 1; }
echo "dsh pid=$DSH_PID，网关就绪"

( cd "$ROOT/dsh-remote-gateway" && DSH_GATEWAY_PASSWORD="$PW" node bin/cli.mjs set-password admin \
    --state-dir "$ROOT/.e2e/dshhome/dsh-remote-gateway" ) >/dev/null || { echo "设置密码失败"; exit 1; }

# ─────────────────────────────────────────────── 4. 端到端验收
step "4. 端到端验收（真实 dsh）"
B="http://127.0.0.1:$GW_PORT" PW="$PW" bash "$ROOT/scripts/e2e-verify.sh" || FAILED+=("端到端验收")

# ─────────────────────────────────────────────── 5. Android APK
step "5. Android APK 构建 + 可安装性自检"
if [ -x "$ROOT/android/build_apk.sh" ] || [ -f "$ROOT/android/build_apk.sh" ]; then
  if ( cd "$ROOT/android" && bash build_apk.sh ) >/dev/null 2>&1; then
    echo "APK: $ROOT/android/dist/DshRemote-debug.apk"
    ( cd "$ROOT/android" && python3 tools/verify_apk.py dist/DshRemote-debug.apk ) || FAILED+=("APK 自检")
  else
    echo "APK 构建失败（工具链缺失？）"
    FAILED+=("APK 构建")
  fi
else
  echo "跳过：找不到 android/build_apk.sh"
fi

# ─────────────────────────────────────────────── 6. Android 网络层
step "6. Android 网络层对真实网关（JVM harness）"
if [ -f "$ROOT/android/verify/run_verify.sh" ]; then
  ( cd "$ROOT/android" && bash verify/run_verify.sh --url "http://127.0.0.1:$GW_PORT" --user admin --pass "$PW" ) \
    || FAILED+=("Android harness")
else
  echo "跳过：找不到 android/verify/run_verify.sh"
fi

# ─────────────────────────────────────────────── 汇总
echo
echo "══════════════════════════════════════════"
if [ "${#FAILED[@]}" -eq 0 ]; then
  echo "  全部通过 ✓"
  echo "══════════════════════════════════════════"
  exit 0
fi
echo "  以下步骤失败："
for f in "${FAILED[@]}"; do echo "    - $f"; done
echo "══════════════════════════════════════════"
exit 1
