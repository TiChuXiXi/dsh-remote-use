#!/usr/bin/env bash
# 搭建一个「只读借用真 profile 依赖」的测试 DSH_HOME。
#
# 为什么需要它：本机沙箱把 / 挂成只读，只有工作区可写，而 dsh 的 profile 目录在
# ~/.dsh 下（不可写）。于是把 DSH_HOME 指到工作区里，用符号链接借用真实 profile
# 的 node_modules —— 读取可以穿越符号链接，因此不需要复制任何依赖。
#
# 这样就能在 **不碰用户正在使用的 web profile** 的前提下，真实启动一个带本插件的
# dsh 实例（不同端口），做端到端验证。
set -euo pipefail

ROOT=/root/project/remote-use
REAL_HOME=/root/.dsh
E2E="$ROOT/.e2e"
DHOME="$E2E/dshhome"
PROFILE="$DHOME/profiles/web"
WS="$E2E/workspace"

DSH_PORT="${DSH_PORT:-3091}"
GW_PORT="${GW_PORT:-3092}"
# 本地跑用 false（纯 http，浏览器不会送 Secure cookie）；
# 经公网 HTTPS 验证时置 true，与生产默认一致。
COOKIE_SECURE="${COOKIE_SECURE:-false}"

rm -rf "$DHOME" "$WS"
mkdir -p "$PROFILE/node_modules" "$WS"

# 1) 借依赖：profiles/node_modules 指向真实的那份（含全部 @deepseek-ai/*）。
ln -sfn "$REAL_HOME/profiles/node_modules" "$DHOME/profiles/node_modules"
# 凭据与设置只读借用，让 web 界面完整可用（本插件不依赖它们）。
[ -f "$REAL_HOME/.credentials.yaml" ] && ln -sfn "$REAL_HOME/.credentials.yaml" "$DHOME/.credentials.yaml"
[ -f "$REAL_HOME/settings.yaml" ] && ln -sfn "$REAL_HOME/settings.yaml" "$DHOME/settings.yaml"

# 2) profile 骨架：cordis.yml 是空入口列表，组合完全由 bundles + patch 决定。
cp "$REAL_HOME/profiles/web/cordis.yml" "$PROFILE/cordis.yml"
cp "$REAL_HOME/profiles/web/pnpm-workspace.yaml" "$PROFILE/pnpm-workspace.yaml"

# 3) 只挂 base + web-app + 本插件，避免第三方插件干扰验证结论。
cat > "$PROFILE/package.json" <<JSON
{
  "name": "dsh-profile-web-e2e",
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

# 4) 插件从工作区真身解析（符号链接即可，无需 pnpm install）。
ln -sfn "$ROOT/dsh-remote-gateway" "$PROFILE/node_modules/dsh-remote-gateway"

# 5) 插件行：测试端口 + 本地 http（cookieSecure 必须关掉，否则 http 下浏览器不送 cookie）
#
# 关键点：把插件列进 dsh.profile.bundles 之后，插件自带的 dsh.bundle.patch
# （也就是它自己的 cordis.patch.yml）**已经**插入了一行 id=remote-gateway。
# 因此这里只能按 id **覆盖**，不能再 insert —— 再 insert 会报
# "duplicate loader entry id: remote-gateway" 并让整棵插件树加载失败。
# 另外 patch 覆盖会替换整行 config，所以每个键都必须重新写全。
cat > "$PROFILE/cordis.patch.yml" <<YAML
- id: remote-gateway
  config:
    gatewayHost: ${GW_HOST:-127.0.0.1}
    gatewayPort: $GW_PORT
    dshPort: 0
    publicAuthority: ''
    workspaceRoot: $WS
    adminPassword: ''
    sessionMaxAgeDays: 30
    cookieSecure: $COOKIE_SECURE
    maxUploadBytes: 2147483648
    replayBodyLimitBytes: ${REPLAY_LIMIT:-67108864}
    loginMaxAttempts: 10
    loginWindowMs: 900000
    auditLog: true
YAML

# 6) 造样本文件（与公网验收共用同一套夹具，见 scripts/make-fixtures.sh）
bash "$ROOT/scripts/make-fixtures.sh" "$WS" "$E2E" >/dev/null

echo "测试 DSH_HOME: $DHOME"
echo "测试工作区:    $WS"
echo "dsh 端口:      $DSH_PORT"
echo "网关端口:      $GW_PORT"
echo
echo "启动命令:"
echo "  cd $WS && DSH_HOME=$DHOME dsh --profile web --port $DSH_PORT --no-open"
