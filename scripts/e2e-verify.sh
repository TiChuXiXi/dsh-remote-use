#!/usr/bin/env bash
# e2e-verify.sh —— 端到端验收：手机 → 隧道 → 插件网关 → dsh 这条链路。
# 直接打真实运行的网关（默认 127.0.0.1:3092），不 mock 任何一层。
#
# 前置：
#   bash scripts/e2e-setup.sh
#   cd .e2e/workspace && DSH_HOME=.../.e2e/dshhome dsh --profile web --port 3091 --no-open &
#   node dsh-remote-gateway/bin/cli.mjs set-password admin --state-dir .../.e2e/dshhome/dsh-remote-gateway
set -uo pipefail

ROOT=/root/project/remote-use
B="${B:-http://127.0.0.1:3092}"
USER_NAME="${USER_NAME:-admin}"
PASSWORD="${PASSWORD:-Str0ngPassw0rd!}"
WS="${WS:-$ROOT/.e2e/workspace}"
STATE="${STATE:-$ROOT/.e2e/dshhome/dsh-remote-gateway}"

PASS=0
FAIL=0
FAILED_NAMES=()

ok()   { echo "  ✓ $1"; PASS=$((PASS + 1)); }
bad()  { echo "  ✗ $1"; FAIL=$((FAIL + 1)); FAILED_NAMES+=("$1"); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 （期望 $2，实际 $3）"; fi
}
section() { echo; echo "── $1 ──"; }

# 只取状态码
code() { curl -s -m 10 -o /dev/null -w '%{http_code}' "$@"; }
# 取 body
body() { curl -s -m 10 "$@"; }
# 取 token
json_field() { node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{try{const o=JSON.parse(s);const p=process.argv[1].split(".");let v=o;for(const k of p)v=v?.[k];process.stdout.write(v===undefined?"":String(v))}catch{process.stdout.write("")}})' "$1"; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

########################################################################
section "1. 健康检查与 fail-closed 解除"
H="$(body "$B/gateway/health")"
check "GET /gateway/health -> 200" "200" "$(code "$B/gateway/health")"
check "  configured=true" "true" "$(printf '%s' "$H" | json_field configured)"

########################################################################
section "2. 登录与凭据签发"
check "错误密码 -> 401" "401" \
  "$(code -X POST -H 'content-type: application/json' -d "{\"username\":\"$USER_NAME\",\"password\":\"wrong-password\"}" "$B/gateway/login")"
check "不存在的用户 -> 401（与错误密码同形）" "401" \
  "$(code -X POST -H 'content-type: application/json' -d '{"username":"nosuchuser","password":"whatever123"}' "$B/gateway/login")"
check "非 JSON 体 -> 400" "400" \
  "$(code -X POST -H 'content-type: application/json' -d 'not-json' "$B/gateway/login")"

LOGIN="$(body -X POST -H 'content-type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login")"
TOKEN="$(printf '%s' "$LOGIN" | json_field token)"
check "正确密码 -> 200" "200" \
  "$(code -X POST -H 'content-type: application/json' -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login")"
if [ -n "$TOKEN" ]; then ok "响应含 token（长度 ${#TOKEN}）"; else bad "响应缺少 token"; fi

SETCOOKIE="$(curl -s -m 10 -D - -o /dev/null -X POST -H 'content-type: application/json' \
  -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login" | tr -d '\r' | grep -i '^set-cookie:' | head -1)"
case "$SETCOOKIE" in
  *drg_session=*) ok "响应带 Set-Cookie: drg_session";;
  *) bad "响应缺少 Set-Cookie: drg_session";;
esac
case "$SETCOOKIE" in *HttpOnly*) ok "cookie 带 HttpOnly";; *) bad "cookie 缺少 HttpOnly";; esac
case "$SETCOOKIE" in *SameSite=Strict*) ok "cookie 带 SameSite=Strict";; *) bad "cookie 缺少 SameSite=Strict";; esac

########################################################################
section "3. 认证判定：Bearer 与 cookie 等价"
check "Bearer -> /gateway/session 200" "200" "$(code -H "Authorization: Bearer $TOKEN" "$B/gateway/session")"
check "Cookie -> /gateway/session 200" "200" "$(code -H "Cookie: drg_session=$TOKEN" "$B/gateway/session")"
check "无凭据 -> 401" "401" "$(code "$B/gateway/session")"
check "篡改的 token -> 401" "401" "$(code -H "Authorization: Bearer ${TOKEN}x" "$B/gateway/session")"
check "伪造签名的 token -> 401" "401" \
  "$(code -H "Authorization: Bearer v1.eyJzdWIiOiJhZG1pbiIsImlhdCI6MSwiZXhwIjo5OTk5OTk5OTk5OTk5LCJqdGkiOiJ4In0.AAAA" "$B/gateway/session")"
check "未登录的 /api/* -> 401" "401" "$(code "$B/api/session/list")"
check "未登录的 /fs/list -> 401" "401" "$(code "$B/fs/list?path=.")"
check "未登录的 / （Accept: text/html）-> 302 到 /login" "302" \
  "$(code -H 'Accept: text/html' "$B/")"
LOC="$(curl -s -m 10 -D - -o /dev/null -H 'Accept: text/html' "$B/" | tr -d '\r' | grep -i '^location:' | head -1)"
case "$LOC" in *'/login'*) ok "  302 的 Location 指向 /login";; *) bad "  302 的 Location 不是 /login（$LOC）";; esac

########################################################################
section "4. 反向代理：真实 dsh 界面与 /api"
check "已登录 GET / -> 200（dsh SPA）" "200" "$(code -H "Authorization: Bearer $TOKEN" "$B/")"
HTML="$(body -H "Authorization: Bearer $TOKEN" "$B/")"
case "$HTML" in *'<div id="root"'*|*'<script'*) ok "  返回的确实是 dsh 的 HTML 外壳";; *) bad "  返回内容不像 dsh SPA";; esac

check "已登录 POST /api/session/list -> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
     -d '{"type":"client-request","rpcId":"probe-1","method":"session/list","payload":{"args":{"_request":{}}}}' \
     "$B/api/session/list")"
RPC="$(body -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/json' \
     -d '{"type":"client-request","rpcId":"probe-2","method":"session/list","payload":{"args":{"_request":{}}}}' \
     "$B/api/session/list")"
check "  RPC 返回 ok=true（证明 dsh 会话 cookie 兑换成功）" "true" "$(printf '%s' "$RPC" | json_field result.ok)"
check "  rpcId 原样回显" "probe-2" "$(printf '%s' "$RPC" | json_field rpcId)"
check "错误 content-type -> 415（dsh 自己的校验也被透传）" "415" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: text/plain' -d 'x' "$B/api/session/list")"

########################################################################
section "5. 工作区文件：列表"
check "GET /fs/list?path=. -> 200" "200" "$(code -H "Authorization: Bearer $TOKEN" "$B/fs/list?path=.")"
LIST="$(body -H "Authorization: Bearer $TOKEN" "$B/fs/list?path=.")"
check "  返回 ok=true" "true" "$(printf '%s' "$LIST" | json_field ok)"
check "  含 build 目录" "build" "$(printf '%s' "$LIST" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const o=JSON.parse(s);const e=o.entries.find(x=>x.name==="build");process.stdout.write(e?e.name:"")})')"
check "  二进制文件的 mime 正确" "application/vnd.android.package-archive" \
  "$(body -H "Authorization: Bearer $TOKEN" "$B/fs/list?path=build/outputs" | node -e 'let s="";process.stdin.on("data",d=>s+=d).on("end",()=>{const o=JSON.parse(s);const e=o.entries.find(x=>x.name==="app-debug.apk");process.stdout.write(e?e.mime:"")})')"
check "  子目录列表 -> 200" "200" "$(code -H "Authorization: Bearer $TOKEN" "$B/fs/list?path=docs")"

########################################################################
section "6. 路径越界防护（双重校验）"
for evil in "../" "../../" "..%2f..%2f" "%2e%2e%2f%2e%2e%2f" "/etc" "/etc/passwd" "docs/../../etc/passwd" "....//....//etc"; do
  check "拒绝 path=$evil -> 403" "403" "$(code -H "Authorization: Bearer $TOKEN" "$B/fs/list?path=$evil")"
done
check "拒绝软链逃逸到工作区外 (escape-link.txt)" "403" \
  "$(code -H "Authorization: Bearer $TOKEN" "$B/fs/download?path=escape-link.txt")"
check "拒绝经由软链目录逃逸 (escape-dir/outside-secret.txt)" "403" \
  "$(code -H "Authorization: Bearer $TOKEN" "$B/fs/download?path=escape-dir/outside-secret.txt")"

########################################################################
section "7. 工作区文件：下载与 Range"
HDRS="$(curl -s -m 10 -D - -o "$TMP/dl.txt" -H "Authorization: Bearer $TOKEN" "$B/fs/download?path=docs/readme.txt" | tr -d '\r')"
check "GET /fs/download -> 200" "200" "$(printf '%s' "$HDRS" | head -1 | awk '{print $2}')"
case "$(printf '%s' "$HDRS" | grep -i '^content-disposition:' || true)" in
  *attachment*) ok "  带 Content-Disposition: attachment";; *) bad "  缺少 Content-Disposition";; esac
case "$(printf '%s' "$HDRS" | grep -i '^accept-ranges:' || true)" in
  *bytes*) ok "  带 Accept-Ranges: bytes";; *) bad "  缺少 Accept-Ranges";; esac
check "  内容正确" "hello from dsh remote gateway" "$(cat "$TMP/dl.txt")"

# Range: 前 1024 字节
curl -s -m 10 -D "$TMP/rh" -o "$TMP/r1" -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=0-1023' "$B/fs/download?path=big.bin" >/dev/null
check "Range: bytes=0-1023 -> 206" "206" "$(head -1 "$TMP/rh" | tr -d '\r' | awk '{print $2}')"
check "  分块大小 1024" "1024" "$(wc -c < "$TMP/r1" | tr -d ' ')"
check "  Content-Range 正确" "bytes 0-1023/1048576" "$(tr -d '\r' < "$TMP/rh" | grep -i '^content-range:' | cut -d' ' -f2-)"
# 断点续传：从 1024 取到结尾
curl -s -m 10 -D "$TMP/rh2" -o "$TMP/r2" -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=1024-' "$B/fs/download?path=big.bin" >/dev/null
check "Range: bytes=1024- -> 206" "206" "$(head -1 "$TMP/rh2" | tr -d '\r' | awk '{print $2}')"
check "  续传长度 = 总长-1024" "$((1048576 - 1024))" "$(wc -c < "$TMP/r2" | tr -d ' ')"
# 两段拼起来必须与原始文件逐字节一致 —— 断点续传的正确性判据
cat "$TMP/r1" "$TMP/r2" > "$TMP/joined.bin"
if cmp -s "$TMP/joined.bin" "$WS/big.bin"; then ok "  分块拼接后与原文件逐字节一致"; else bad "  分块拼接结果与原文件不一致"; fi
check "越界 Range -> 416" "416" "$(code -H "Authorization: Bearer $TOKEN" -H 'Range: bytes=99999999-' "$B/fs/download?path=big.bin")"

########################################################################
section "8. 工作区文件：三种上传模式"
# A) 原始体
printf 'raw-mode-content\n' > "$TMP/raw.txt"
check "A) 原始体上传 -> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/octet-stream' \
     --data-binary @"$TMP/raw.txt" "$B/fs/upload?path=nested/deep/raw.txt")"
check "  A) 文件已落盘且内容正确" "raw-mode-content" "$(cat "$WS/nested/deep/raw.txt" 2>/dev/null)"

# B) 分块（3 块）
head -c 300000 /dev/urandom > "$TMP/chunked.bin"
SHA="$(sha256sum "$TMP/chunked.bin" | cut -d' ' -f1)"
split -b 100000 -d -a 1 "$TMP/chunked.bin" "$TMP/part."
CI=0
for f in "$TMP"/part.*; do
  H=(-H "Authorization: Bearer $TOKEN" -H "X-Upload-Id: e2etest01" -H "X-Chunk-Index: $CI" -H "X-Total-Chunks: 3" -H 'X-File-Path: nested/deep/chunked.bin')
  if [ "$CI" -eq 2 ]; then H+=(-H "X-File-SHA256: $SHA"); fi
  RESP="$(body -X POST "${H[@]}" -H 'content-type: application/octet-stream' --data-binary @"$f" "$B/fs/upload")"
  CI=$((CI + 1))
done
check "B) 分块合并完成 completed=true" "true" "$(printf '%s' "$RESP" | json_field completed)"
check "  B) 服务端 SHA-256 与客户端一致" "$SHA" "$(printf '%s' "$RESP" | json_field sha256)"
if cmp -s "$TMP/chunked.bin" "$WS/nested/deep/chunked.bin"; then ok "  B) 合并结果与原文件逐字节一致"; else bad "  B) 合并结果不一致"; fi

# B2) 分块缺块 -> completed=false
printf 'x' > "$TMP/one.bin"
R="$(body -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Upload-Id: e2epartial' -H 'X-Chunk-Index: 0' -H 'X-Total-Chunks: 3' \
  -H 'X-File-Path: nested/deep/partial.bin' -H 'content-type: application/octet-stream' \
  --data-binary @"$TMP/one.bin" "$B/fs/upload")"
check "B2) 只发 1/3 块时 completed=false" "false" "$(printf '%s' "$R" | json_field completed)"
if [ -f "$WS/nested/deep/partial.bin" ]; then bad "B2) 未完成的分块不应在目标路径留下半成品"; else ok "B2) 未完成时目标路径无半成品（原子落盘生效）"; fi

# B3) 哈希不匹配必须拒绝
R="$(body -X POST -H "Authorization: Bearer $TOKEN" -H 'X-Upload-Id: e2ebad' -H 'X-Chunk-Index: 0' -H 'X-Total-Chunks: 1' \
  -H 'X-File-Path: nested/deep/bad.bin' -H 'X-File-SHA256: 0000000000000000000000000000000000000000000000000000000000000000' \
  -H 'content-type: application/octet-stream' --data-binary @"$TMP/one.bin" "$B/fs/upload")"
check "B3) SHA-256 不匹配 -> checksum_mismatch" "checksum_mismatch" "$(printf '%s' "$R" | json_field error)"

# C) multipart（path 字段放在 file **之后**，验证解析器与字段顺序无关）
printf 'multipart-content\n' > "$TMP/mp.txt"
R="$(curl -s -m 15 -X POST -H "Authorization: Bearer $TOKEN" \
  -F "file=@$TMP/mp.txt;filename=mp-upload.txt" -F 'path=nested/deep/mp-upload.txt' "$B/fs/upload")"
check "C) multipart 上传 -> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -F "file=@$TMP/mp.txt;filename=mp2.txt" -F 'path=nested/deep/mp2.txt' "$B/fs/upload")"
check "  C) completed=true" "true" "$(printf '%s' "$R" | json_field completed)"
check "  C) 内容正确" "multipart-content" "$(cat "$WS/nested/deep/mp-upload.txt" 2>/dev/null)"
check "C2) 上传到越界路径 -> 403" "403" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/octet-stream' --data-binary @"$TMP/one.bin" "$B/fs/upload?path=../../pwned.bin")"
if [ -f "$ROOT/pwned.bin" ] || [ -f "$(dirname "$WS")/pwned.bin" ]; then bad "C2) 越界上传竟然写成功了"; else ok "C2) 工作区外未出现文件"; fi

# D) 目标为**已存在目录**（契约规定：文件名取上传文件自身的 basename）
printf 'into-root\n' > "$TMP/intoroot.txt"
check "D) multipart + path=. （工作区根）-> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -F "file=@$TMP/intoroot.txt;filename=into-root.txt" -F 'path=.' "$B/fs/upload")"
check "  D) 文件落在工作区根" "into-root" "$(cat "$WS/into-root.txt" 2>/dev/null)"
check "D2) multipart + path=docs （已有目录）-> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -F "file=@$TMP/intoroot.txt;filename=into-docs.txt" -F 'path=docs' "$B/fs/upload")"
check "  D2) 文件落在 docs/" "into-root" "$(cat "$WS/docs/into-docs.txt" 2>/dev/null)"
check "D3) 原始体 + path=. + X-File-Name -> 200" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/octet-stream' -H 'X-File-Name: raw-root.txt' --data-binary @"$TMP/intoroot.txt" "$B/fs/upload?path=.")"
check "  D3) 文件落在工作区根" "into-root" "$(cat "$WS/raw-root.txt" 2>/dev/null)"
check "D4) X-File-Name 里的目录成分被剥掉（不能借此逃逸）" "200" \
  "$(code -X POST -H "Authorization: Bearer $TOKEN" -H 'content-type: application/octet-stream' -H 'X-File-Name: ../../evil.txt' --data-binary @"$TMP/intoroot.txt" "$B/fs/upload?path=.")"
if [ -f "$(dirname "$WS")/evil.txt" ]; then bad "D4) 借助 X-File-Name 逃逸成功了"; else ok "  D4) 工作区外未出现 evil.txt"; fi

########################################################################
section "9. WebSocket 流式（真实帧往返，不只是 101）"
WSURL="ws://127.0.0.1:3092/api/remote.mux"
if node "$ROOT/dsh-remote-gateway/tests/ws-probe.mjs" "$WSURL" "$TOKEN" '$events' 10000 > "$TMP/ws.log" 2>&1; then
  ok "WebSocket 升级 + 真实数据帧往返"
  sed 's/^/      /' "$TMP/ws.log" | head -4
else
  bad "WebSocket 探测失败"
  sed 's/^/      /' "$TMP/ws.log" | head -8
fi
if curl -s -m 6 -o /dev/null -w '%{http_code}' -H "Connection: Upgrade" -H "Upgrade: websocket" \
   -H "Sec-WebSocket-Version: 13" -H "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==" \
   "$B/api/remote.mux" 2>/dev/null | grep -q '401'; then
  ok "未认证的 WebSocket 升级被拒（401）"
else
  bad "未认证的 WebSocket 升级未被拒绝"
fi

########################################################################
section "10. 登录限速（用独立 XFF 来源，避免污染上面的 IP）"
RL=0
for i in $(seq 1 12); do
  C="$(code -X POST -H 'content-type: application/json' -H 'X-Forwarded-For: 203.0.113.77' \
    -d '{"username":"admin","password":"nope-nope-nope"}' "$B/gateway/login")"
  if [ "$C" = "429" ]; then RL=$i; break; fi
done
if [ "$RL" -eq 11 ]; then ok "第 11 次失败尝试触发 429"; 
elif [ "$RL" -gt 0 ]; then bad "限速在第 $RL 次触发（期望第 11 次）"; 
else bad "连续 12 次失败登录未触发限速"; fi
check "  被限速的 IP 不受影响其它来源（本机仍可登录）" "200" \
  "$(code -X POST -H 'content-type: application/json' -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login")"

########################################################################
section "11. 安全不变量"
echo -n "  · dsh 自身直连（3091）未登录 -> "
D="$(code http://127.0.0.1:3091/)"; if [ "$D" = "401" ]; then ok "401（dsh 自己的认证仍在）"; else bad "期望 401，实际 $D"; fi
echo -n "  · dsh 自身 /api 直连未登录 -> "
D="$(code -X POST -H 'content-type: application/json' -d '{}' http://127.0.0.1:3091/api/session/list)"
if [ "$D" = "401" ] || [ "$D" = "403" ]; then ok "$D（未被网关以外的方式打开）"; else bad "期望 401/403，实际 $D"; fi
check "  · 网关绑定在回环地址（非 0.0.0.0）" "1" \
  "$( (ss -ltn 2>/dev/null || netstat -ltn) | grep -c '127.0.0.1:3092' )"
check "  · 网关未监听 0.0.0.0:3092" "0" \
  "$( (ss -ltn 2>/dev/null || netstat -ltn) | grep -c '0.0.0.0:3092' )"
if [ -f "$STATE/users.json" ]; then
  if grep -q "Str0ngPassw0rd" "$STATE/users.json" 2>/dev/null; then bad "  · users.json 里出现明文密码"; else ok "  · users.json 不含明文密码"; fi
  PERM="$(stat -c '%a' "$STATE/users.json")"
  check "  · users.json 权限 600" "600" "$PERM"
else
  bad "  · users.json 不存在"
fi
if [ -f "$STATE/session.key" ]; then
  check "  · session.key 权限 600" "600" "$(stat -c '%a' "$STATE/session.key")"
else
  bad "  · session.key 不存在"
fi
if [ -f "$STATE/audit.log" ]; then
  ok "  · 审计日志已生成（$(wc -l < "$STATE/audit.log" | tr -d ' ') 条）"
  for a in login.success login.failure fs.denied fs.upload auth.rejected; do
    if grep -q "\"$a\"" "$STATE/audit.log"; then ok "    审计含 $a"; else bad "    审计缺少 $a"; fi
  done
else
  bad "  · 审计日志缺失"
fi

########################################################################
section "12. 登出与吊销"
check "POST /gateway/logout -> 200" "200" "$(code -X POST -H "Authorization: Bearer $TOKEN" "$B/gateway/logout")"
check "  登出后该 Bearer token 立即失效（不只是清 cookie）-> 401" "401" \
  "$(code -H "Authorization: Bearer $TOKEN" "$B/gateway/session")"
RELOGIN="$(body -X POST -H 'content-type: application/json' -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login")"
NEWTOKEN="$(printf '%s' "$RELOGIN" | json_field token)"
check "  重新登录后新 token 可用 -> 200" "200" "$(code -H "Authorization: Bearer $NEWTOKEN" "$B/gateway/session")"
check "POST /gateway/revoke-all -> 200" "200" "$(code -X POST -H "Authorization: Bearer $NEWTOKEN" "$B/gateway/revoke-all")"
check "  吊销后该 token 失效 -> 401" "401" "$(code -H "Authorization: Bearer $NEWTOKEN" "$B/gateway/session")"
T3="$(printf '%s' "$(body -X POST -H 'content-type: application/json' -d "{\"username\":\"$USER_NAME\",\"password\":\"$PASSWORD\"}" "$B/gateway/login")" | json_field token)"
check "  吊销后立刻重登的新 token 可用（同毫秒竞态回归）-> 200" "200" "$(code -H "Authorization: Bearer $T3" "$B/gateway/session")"

########################################################################
echo
echo "════════════════════════════════════════════"
echo "  通过: $PASS    失败: $FAIL"
if [ "$FAIL" -gt 0 ]; then
  echo "  失败项:"
  for n in "${FAILED_NAMES[@]}"; do echo "    - $n"; done
fi
echo "════════════════════════════════════════════"
[ "$FAIL" -eq 0 ]
