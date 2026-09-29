#!/usr/bin/env bash
# verify-all.sh —— 一条命令跑完全部**可复现**的验证。
#
#   1. 插件编译
#   2. 插件单元测试
#   3. Android APK 构建 + 可安装性自检（不需要 dsh）
#   4. 起真实 dsh（测试 DSH_HOME，不碰在用的 profile）+ 插件网关
#   5. Android 网络层对真实网关跑 JVM harness
#   6. 端到端验收 95 项（含真实 WebSocket 帧往返）
#   7. 收尾
#
# ⚠ 步骤 5 必须在 6 之前：端到端套件最后的限速小节会打满真实客户端 IP 的失败计数桶，
#   之后任何登录都会 429（经 Cloudflare 时尤其必然，因为伪造 X-Forwarded-For 无法
#   造出独立桶）。所以「需要登录的检查」一律排在限速小节之前。
#
# 公网链路验证见 scripts/e2e-public.sh。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DSH_PORT="${DSH_PORT:-3091}"
GW_PORT="${GW_PORT:-3092}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh.log"

FAILED=()
step() { echo; echo "══════════════════════════════════════════"; echo "  $1"; echo "══════════════════════════════════════════"; }

DSH_PID=""
cleanup() {
  if [ -n "$DSH_PID" ] && kill -0 "$DSH_PID" 2>/dev/null; then
    kill -TERM "$DSH_PID" 2>/dev/null; sleep 1; kill -KILL "$DSH_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

# ─────────────────────────────────────────── 1-2. 编译 + 单元测试
step "1-2. 插件编译 + 单元测试"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) || { echo "编译失败"; exit 1; }
( cd "$ROOT/dsh-remote-gateway" && node --test tests/unit.test.mjs ) || FAILED+=("单元测试")

# ─────────────────────────────────────────── 3. Android APK
step "3. Android APK 构建 + 可安装性自检"
if [ -f "$ROOT/android/build_apk.sh" ]; then
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

# ─────────────────────────────────────────── 3b. Android UI 层（Robolectric）
step "3b. Android UI 层：Gradle 构建 + Robolectric 真跑 Activity"
if [ -x "$ROOT/android/gradlew" ]; then
  ( cd "$ROOT/android" && ./gradlew :app:assembleDebug :app:testDebugUnitTest ) >"$ROOT/.e2e/gradle.log" 2>&1
  if [ $? -eq 0 ]; then
    TOT=0; BADN=0
    for f in "$ROOT"/android/app/build/test-results/testDebugUnitTest/*.xml; do
      [ -f "$f" ] || continue
      T=$(grep -o 'tests="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
      F=$(grep -o 'failures="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
      E=$(grep -o 'errors="[0-9]*"' "$f" | head -1 | grep -o '[0-9]*')
      TOT=$((TOT + ${T:-0})); BADN=$((BADN + ${F:-0} + ${E:-0}))
    done
    echo "Robolectric: $TOT 项，失败 $BADN（日志 $ROOT/.e2e/gradle.log）"
    [ "$BADN" -eq 0 ] || FAILED+=("Robolectric UI 测试（$BADN 项失败）")
  else
    echo "Gradle 构建/测试失败，日志末尾："; tail -20 "$ROOT/.e2e/gradle.log"
    FAILED+=("Gradle 构建或 Robolectric 测试")
  fi
else
  echo "跳过：找不到 android/gradlew（先跑 android/tools/setup_toolchain.sh）"
fi

# ─────────────────────────────────────────── 4. 起真实 dsh + 网关
step "4. 搭建测试环境并启动真实 dsh（端口 $DSH_PORT，网关 $GW_PORT）"
DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || { echo "环境搭建失败"; exit 1; }

cd "$ROOT/.e2e/workspace"
DSH_HOME="$ROOT/.e2e/dshhome" setsid nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!

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

# ─────────────────────────────────────────── 5. Android 网络层（须早于端到端）
step "5. Android 网络层对真实网关（JVM harness）"
if [ -f "$ROOT/android/verify/run_verify.sh" ]; then
  ( cd "$ROOT/android" && bash verify/run_verify.sh --url "http://127.0.0.1:$GW_PORT" --user admin --pass "$PW" ) \
    || FAILED+=("Android harness")
else
  echo "跳过：找不到 android/verify/run_verify.sh"
fi

# ─────────────────────────────────────────── 6. 端到端（限速在最后）
step "6. 端到端验收（真实 dsh）"
B="http://127.0.0.1:$GW_PORT" PW="$PW" bash "$ROOT/scripts/e2e-verify.sh" || FAILED+=("端到端验收")

# ─────────────────────────────────────────── 7. 大文件专项（自带环境，放最后）
# 自带 setup + 起 dsh，会覆盖 .e2e/dshhome，所以必须排在前面那些步骤之后。
step "7. 大文件专项（上传三模式 / 经代理的大上传 / Range 续传）"
# 这一步会 rm -rf .e2e，而上面第 4 步把 cwd 切到了 .e2e/workspace —— 先挪回不会被删的地方，
# 否则后续每个子进程都会刷 getcwd 报错。
cd "$ROOT"
# 默认体积很大（合计约 1.5 GiB）；设 LARGE_QUICK=1 可只跑一遍小样本。
if [ "${LARGE_QUICK:-0}" = "1" ]; then
  RAW_MB=32 MP_MB=16 CHUNK_MB=32 PROXY_MB=16 \
    bash "$ROOT/scripts/e2e-largefile.sh" || FAILED+=("大文件专项")
else
  bash "$ROOT/scripts/e2e-largefile.sh" || FAILED+=("大文件专项")
fi

# ─────────────────────────────────────────── 8. 网关绑定内网 IP
step "8. 网关绑定内网 IP（生产拓扑）"
bash "$ROOT/scripts/verify-lan-bind.sh" || FAILED+=("内网绑定验证")

# ─────────────────────────────────────────── 汇总
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
