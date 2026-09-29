#!/usr/bin/env bash
# e2e-largefile.sh —— 大文件专项验收：上传（三种模式）、经反向代理的大上传、断点续传下载。
#
# 为什么单独一个脚本：常规 e2e 用的样本最大只有 1 MiB，**证明不了大文件路径**。
# 大文件出问题的地方和它们完全不同：
#   1. 反向代理的「可重放体积」阈值 —— 超过它就走流式转发，失去 401 重放能力；
#   2. 内存 —— 若实现把整个请求体读进内存，几百 MB 就会把 dsh 进程顶爆；
#   3. 分块/原子落盘在真正的体积下是否还成立；
#   4. Range 续传在大文件上拼接是否逐字节一致。
#
# 用法：
#   bash scripts/e2e-largefile.sh              # 默认 RAW=512 MP=256 CHUNK=512 PROXY=256 (MiB)
#   RAW_MB=64 MP_MB=32 CHUNK_MB=64 PROXY_MB=32 bash scripts/e2e-largefile.sh   # 快速跑一遍
set -uo pipefail

ROOT=/root/project/remote-use
# 刻意用与 verify-all.sh 主流程（3091/3092）**不同**的端口：本脚本会 rm -rf .e2e
# 重建环境，若和主流程那个仍在运行的 dsh 抢同一个端口，行为就不确定了。
DSH_PORT="${DSH_PORT:-3095}"
GW_PORT="${GW_PORT:-3096}"
PW="${PW:-Str0ngPassw0rd!}"
LOG="$ROOT/.e2e/dsh.log"
WS="$ROOT/.e2e/workspace"
B="http://127.0.0.1:$GW_PORT"

# 本脚本会 rm -rf 自己所在的目录树（.e2e），cwd 必须钉在一个不会被删的地方，
# 否则后续每个子进程都会报 getcwd 失败。
cd "$ROOT"

# 各样本体积（MiB）。默认值刻意超过默认重放阈值 64 MiB，以覆盖流式转发路径。
RAW_MB="${RAW_MB:-512}"     # 模式 C 原始体
MP_MB="${MP_MB:-256}"       # 模式 A multipart
CHUNK_MB="${CHUNK_MB:-512}" # 模式 B 分块
PROXY_MB="${PROXY_MB:-256}" # 经反向代理打到 dsh 自己的上传端点

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 （期望 $2，实际 $3）"; fi }
section() { echo; echo "── $1 ──"; }
json_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const o=JSON.parse(s);const p=process.argv[1].split(".");let v=o;for(const k of p)v=v?.[k];process.stdout.write(v===undefined?"":String(v))}catch{process.stdout.write("")}})' "$1"; }
mib() { echo "$(( $1 * 1024 * 1024 ))"; }

TMP="$(mktemp -d)"
DSH_PID=""
cleanup() {
  rm -rf "$TMP"
  if [ -n "$DSH_PID" ] && kill -0 "$DSH_PID" 2>/dev/null; then
    kill -TERM "$DSH_PID" 2>/dev/null; sleep 1; kill -KILL "$DSH_PID" 2>/dev/null
  fi
}
trap cleanup EXIT

step() { echo; echo "══════════════════════════════════════════"; echo "  $1"; echo "══════════════════════════════════════════"; }

step "1/6 编译 + 搭建测试环境"
( cd "$ROOT/dsh-remote-gateway" && npm run build ) >/dev/null || { echo "编译失败"; exit 1; }
DSH_PORT="$DSH_PORT" GW_PORT="$GW_PORT" bash "$ROOT/scripts/e2e-setup.sh" >/dev/null || { echo "环境搭建失败"; exit 1; }
echo "ok（工作区 $WS）"

step "2/6 启动真实 dsh（端口 $DSH_PORT，网关 $GW_PORT）"
cd "$WS"
# 不用 setsid：它会 fork，于是 $! 拿到的是那个立刻退出的父进程，后面量 RSS 就量了个空气。
# 这里不需要脱离会话 —— 脚本自己负责在退出时杀掉它。nohup 是 exec，$! 就是 dsh 本身。
DSH_HOME="$ROOT/.e2e/dshhome" nohup dsh --profile web --port "$DSH_PORT" --no-open >"$LOG" 2>&1 &
DSH_PID=$!
for _ in $(seq 1 45); do
  curl -s -m 2 -o /dev/null "$B/gateway/health" && break
  kill -0 "$DSH_PID" 2>/dev/null || { echo "dsh 提前退出："; tail -25 "$LOG"; exit 1; }
  sleep 1
done
curl -s -m 2 -o /dev/null "$B/gateway/health" || { echo "网关未就绪："; tail -25 "$LOG"; exit 1; }
( cd "$ROOT/dsh-remote-gateway" && DSH_GATEWAY_PASSWORD="$PW" node bin/cli.mjs set-password admin \
    --state-dir "$ROOT/.e2e/dshhome/dsh-remote-gateway" ) >/dev/null || { echo "设置密码失败"; exit 1; }

# 兜底：万一 $! 仍不是 dsh（例如换了启动方式），按命令行找回来。
if ! kill -0 "$DSH_PID" 2>/dev/null || [ -z "$(ps -o rss= -p "$DSH_PID" 2>/dev/null | tr -d ' ')" ]; then
  FOUND="$(pgrep -f "dsh --profile web --port $DSH_PORT" 2>/dev/null | head -1)"
  [ -n "$FOUND" ] && DSH_PID="$FOUND"
fi
echo "dsh pid=$DSH_PID，网关就绪"

rss_kb() { ps -o rss= -p "$DSH_PID" 2>/dev/null | tr -d ' '; }
RSS_BASE="$(rss_kb)"
if [ -n "$RSS_BASE" ]; then echo "dsh 起始 RSS: ${RSS_BASE} KB"; else echo "⚠ 量不到 dsh RSS（pid=$DSH_PID）"; fi

step "3/6 登录 + 建会话"
TOKEN="$(curl -s -m 10 -X POST -H 'content-type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$PW\"}" "$B/gateway/login" | json_field token)"
if [ -n "$TOKEN" ]; then ok "登录拿到 token"; else bad "登录失败"; echo "无法继续"; exit 1; fi

SID="$(curl -s -m 30 -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
  -d '{"type":"client-request","rpcId":"lf-create","method":"session/create","payload":{"args":{"request":{}}}}' \
  "$B/api/session/create" | json_field result.value.sessionId)"
if [ -n "$SID" ]; then ok "session/create -> $SID"; else bad "session/create 未返回 sessionId（代理大上传测试将跳过）"; fi

step "4/6 大文件上传（网关自有 /fs/upload 三种模式）"
mkdir -p "$WS/large"

# ---- 模式 C：原始体，最大的一档 ----
section "模式 C（原始体） ${RAW_MB} MiB"
head -c "$(mib "$RAW_MB")" /dev/urandom > "$TMP/raw.bin"
RAW_SHA="$(sha256sum "$TMP/raw.bin" | cut -d' ' -f1)"
T0=$SECONDS
RESP="$(curl -s -m 900 -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/octet-stream' \
  -H "X-File-SHA256: $RAW_SHA" -T "$TMP/raw.bin" "$B/fs/upload?path=large/raw.bin")"
T1=$SECONDS
check "C) 上传 -> ok=true" "true" "$(printf '%s' "$RESP" | json_field ok)"
check "C) 服务端 sha256 与客户端一致" "$RAW_SHA" "$(printf '%s' "$RESP" | json_field sha256)"
if [ -f "$WS/large/raw.bin" ] && cmp -s "$TMP/raw.bin" "$WS/large/raw.bin"; then
  ok "C) 落盘文件与原文件逐字节一致"
else
  bad "C) 落盘文件不一致或不存在"
fi
echo "     ${RAW_MB} MiB 用时 $((T1 - T0))s"

# ---- 模式 A：multipart ----
section "模式 A（multipart） ${MP_MB} MiB"
head -c "$(mib "$MP_MB")" /dev/urandom > "$TMP/mp.bin"
MP_SHA="$(sha256sum "$TMP/mp.bin" | cut -d' ' -f1)"
T0=$SECONDS
RESP="$(curl -s -m 900 -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@$TMP/mp.bin;filename=mp.bin" -F 'path=large' "$B/fs/upload")"
T1=$SECONDS
check "A) 上传 -> ok=true" "true" "$(printf '%s' "$RESP" | json_field ok)"
check "A) 服务端 sha256 与客户端一致" "$MP_SHA" "$(printf '%s' "$RESP" | json_field sha256)"
if cmp -s "$TMP/mp.bin" "$WS/large/mp.bin"; then ok "A) 落盘文件逐字节一致"; else bad "A) 落盘文件不一致"; fi
echo "     ${MP_MB} MiB 用时 $((T1 - T0))s"

# ---- 模式 B：分块 ----
section "模式 B（分块 4 块） ${CHUNK_MB} MiB"
head -c "$(mib "$CHUNK_MB")" /dev/urandom > "$TMP/chunk.bin"
CHUNK_SHA="$(sha256sum "$TMP/chunk.bin" | cut -d' ' -f1)"
CHUNK_BYTES=$(( CHUNK_MB * 1024 * 1024 / 4 ))
split -b "$CHUNK_BYTES" -d -a 1 "$TMP/chunk.bin" "$TMP/cpart."
UPID="lf$(date +%s)"
T0=$SECONDS
CI=0
for f in "$TMP"/cpart.*; do
  R="$(curl -s -m 900 -X POST -H "Authorization: Bearer $TOKEN" \
    -H "X-Upload-Id: $UPID" -H "X-Chunk-Index: $CI" -H 'X-Total-Chunks: 4' \
    -H 'X-File-Path: large/chunked.bin' -H "X-File-SHA256: $CHUNK_SHA" \
    -H 'content-type: application/octet-stream' -T "$f" "$B/fs/upload")"
  check "B) 第 $CI 块 accepted" "true" "$(printf '%s' "$R" | json_field ok)"
  CI=$((CI + 1))
done
T1=$SECONDS
check "B) completed=true" "true" "$(printf '%s' "$R" | json_field completed)"
if cmp -s "$TMP/chunk.bin" "$WS/large/chunked.bin"; then
  ok "B) 合并结果与原文件逐字节一致"
else
  bad "B) 合并结果不一致"
fi
echo "     ${CHUNK_MB} MiB / 4 块用时 $((T1 - T0))s"

step "5/6 经反向代理的大上传（走 dsh 自己的上传端点）"
section "POST /api/session/uploadFileBinary  ${PROXY_MB} MiB"
if [ -z "$SID" ]; then
  bad "没有 sessionId，跳过"
else
  head -c "$(mib "$PROXY_MB")" /dev/urandom > "$TMP/proxy.bin"
  PROXY_SHA="$(sha256sum "$TMP/proxy.bin" | cut -d' ' -f1)"
  T0=$SECONDS
  # 关键：这个体积 > replayBodyLimitBytes(64 MiB)，所以网关走的是**流式转发**路径。
  PRESP="$(curl -s -m 900 -X POST -H "Authorization: Bearer $TOKEN" \
    -H 'content-type: application/octet-stream' \
    -T "$TMP/proxy.bin" "$B/api/session/uploadFileBinary?sessionId=$SID&name=large-proxy.bin")"
  T1=$SECONDS
  check "代理) HTTP 层返回 ok=true" "true" "$(printf '%s' "$PRESP" | json_field ok)"
  PSIZE="$(printf '%s' "$PRESP" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const o=JSON.parse(s);const v=o.value||{};const f=v.file||{};process.stdout.write(String(f.bytes??v.size??v.bytes??""))}catch{process.stdout.write("")}})')"
  if [ "$PSIZE" = "$(mib "$PROXY_MB")" ]; then
    ok "代理) dsh 收到的字节数 = $PSIZE（完整送达，未被截断）"
  else
    bad "代理) dsh 收到的字节数 = ${PSIZE:-未知}，期望 $(mib "$PROXY_MB")"
  fi
  echo "     ${PROXY_MB} MiB 用时 $((T1 - T0))s"
  echo "     响应: $(printf '%s' "$PRESP" | head -c 200)"
fi

step "6/6 大文件下载与 Range 断点续传"
section "下载 ${RAW_MB} MiB + 从 60% 处续传"
TOTAL="$(mib "$RAW_MB")"
CUT=$(( TOTAL * 60 / 100 ))
curl -s -m 900 -H "Authorization: Bearer $TOKEN" -H "Range: bytes=0-$((CUT - 1))" \
  -o "$TMP/dl-part1" "$B/fs/download?path=large/raw.bin"
check "Range 第一段 -> 字节数" "$CUT" "$(wc -c < "$TMP/dl-part1" | tr -d ' ')"
curl -s -m 900 -H "Authorization: Bearer $TOKEN" -H "Range: bytes=$CUT-" \
  -o "$TMP/dl-part2" "$B/fs/download?path=large/raw.bin"
check "Range 第二段 -> 字节数" "$((TOTAL - CUT))" "$(wc -c < "$TMP/dl-part2" | tr -d ' ')"
cat "$TMP/dl-part1" "$TMP/dl-part2" > "$TMP/dl-joined"
if [ "$(sha256sum "$TMP/dl-joined" | cut -d' ' -f1)" = "$RAW_SHA" ]; then
  ok "两段拼接后与原文件 sha256 一致"
else
  bad "拼接结果 sha256 不一致"
fi

section "内存占用（证明是流式，不是把整个文件读进内存）"
RSS_NOW="$(rss_kb)"
# 量不到就必须报失败 —— 空值会让 $(( )) 当 0 算，得出「负增长」然后假装通过，
# 那等于用一个假数据换一个绿勾。
if [ -z "$RSS_BASE" ] || [ -z "$RSS_NOW" ]; then
  bad "量不到 dsh RSS（起始='${RSS_BASE}' 现在='${RSS_NOW}'），无法证明内存行为"
else
  GROWTH_KB=$(( RSS_NOW - RSS_BASE ))
  GROWTH_MB=$(( GROWTH_KB / 1024 ))
  echo "     dsh RSS: 起始 ${RSS_BASE} KB → 现在 ${RSS_NOW} KB（增长 ${GROWTH_MB} MiB）"
  # 处理过 RAW+MP+CHUNK+PROXY 合计约 1.5 GiB 的数据；若实现是「整体读进内存」，
  # RSS 必然涨到 GiB 级别。留 512 MiB 的余量已经非常宽松。
  if [ "$GROWTH_MB" -lt 512 ]; then
    ok "RSS 增长 ${GROWTH_MB} MiB < 512 MiB（流式转发/落盘，无整体缓冲）"
  else
    bad "RSS 增长 ${GROWTH_MB} MiB，疑似把请求体整体读进了内存"
  fi
fi

echo
echo "════════════════════════════════════════════"
if [ "$FAIL" -eq 0 ]; then
  echo "  大文件验收全部通过 ✓   通过: $PASS  失败: $FAIL"
  echo "════════════════════════════════════════════"
  exit 0
fi
echo "  大文件验收有失败项：通过 $PASS，失败 $FAIL"
for n in "${FAILED_NAMES[@]}"; do echo "    - $n"; done
echo "════════════════════════════════════════════"
exit 1
