#!/usr/bin/env bash
# tunnel-up.sh —— 起 Cloudflare **Named Tunnel**，指向插件网关。
#
# 用法：
#   bash scripts/tunnel-up.sh                 # 前台跑（Ctrl-C 退出）
#   bash scripts/tunnel-up.sh --daemon        # 后台跑，日志写 .runtime/cf-named.log
#   CF_TUNNEL_TOKEN=xxx bash scripts/tunnel-up.sh
#
# Token 读取顺序：$CF_TUNNEL_TOKEN > .runtime/cf-token
#
# ── 你需要在 Cloudflare 控制台做的那一步 ──────────────────────────────────
# 本隧道是「远程管理」的：ingress 规则不在本机，而在控制台。
# 进 Zero Trust → Networks → Tunnels → 选中本隧道 → Public Hostname → Add：
#
#     Subdomain : 你想要的子域
#     Domain    : 你的域名
#     Type      : HTTP
#     URL       : 192.168.1.182:3081        ← 就是这一行，别填 127.0.0.1
#
# 填内网 IP 而不是回环，是为了在控制台一眼认出「这是网关」，而不是别的本地服务。
# 网关自己只监听这个地址（见 dsh-remote-gateway/cordis.patch.yml 的 gatewayHost）。
#
# ── 为什么要 TUNNEL_EDGE ────────────────────────────────────────────────
# 本机 DNS（192.168.1.1，走 Meta/Clash 的 fake-IP）**不返回 SRV 记录**，
# cloudflared 的边缘发现 `_v2-origintunneld._tcp.argotunnel.com` 会失败并
# 让 precheck hard_fail。显式给出边缘地址即可绕过 SRV 查询 —— 这是实测结论，
# 不是猜的（scripts/e2e-public.sh 里对 Quick Tunnel 用的是同一招）。
set -uo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
LOG="$ROOT/.runtime/cf-named.log"
EDGE="region1.v2.argotunnel.com:7844,region2.v2.argotunnel.com:7844"

TOKEN="${CF_TUNNEL_TOKEN:-}"
if [ -z "$TOKEN" ] && [ -f "$ROOT/.runtime/cf-token" ]; then
  TOKEN="$(tr -d '\n' < "$ROOT/.runtime/cf-token")"
fi
if [ -z "$TOKEN" ]; then
  echo "缺少隧道 token：设 CF_TUNNEL_TOKEN，或写到 .runtime/cf-token" >&2
  exit 1
fi

if ! command -v cloudflared >/dev/null 2>&1; then
  echo "找不到 cloudflared。安装：" >&2
  echo "  curl -fsSl https://pkg.cloudflare.com/cloudflared-ascii.repo | tee /etc/yum.repos.d/cloudflared.repo" >&2
  echo "  yum install -y cloudflared" >&2
  exit 1
fi

mkdir -p "$ROOT/.runtime"

if [ "${1:-}" = "--daemon" ]; then
  # setsid + nohup：脱离当前会话，关掉终端也不会把隧道带走。
  TUNNEL_EDGE="$EDGE" setsid nohup cloudflared tunnel run --token "$TOKEN" \
    >"$LOG" 2>&1 </dev/null &
  echo "隧道已在后台启动，pid=$!"
  echo "日志：$LOG"
  echo "查看是否连上边缘：grep 'Registered tunnel connection' $LOG"
else
  echo "前台运行（Ctrl-C 退出）。日志同时写到 $LOG"
  TUNNEL_EDGE="$EDGE" cloudflared tunnel run --token "$TOKEN" 2>&1 | tee "$LOG"
fi
