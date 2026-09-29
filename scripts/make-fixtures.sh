#!/usr/bin/env bash
# make-fixtures.sh —— 在指定工作区造一套样本文件，覆盖列表/下载/Range/上传/越界各种情形。
#
# 抽出来是因为两处都要用：
#   - scripts/e2e-setup.sh         （直连拓扑的测试工作区）
#   - scripts/verify-public-named.sh（公网拓扑的生产工作区）
# 之前只有前者会造，于是拿公网脚本打生产实例时，所有 /fs/* 用例都因为
# 「工作区是空的」而 404 —— 看起来像产品坏了，其实是夹具没造。
#
# 用法：bash scripts/make-fixtures.sh <workspace> [outside-dir]
set -euo pipefail

WS="${1:?用法: make-fixtures.sh <workspace> [outside-dir]}"
OUTSIDE="${2:-$(dirname "$WS")}"

# 幂等：先清掉本脚本拥有的那几个路径再重建。
# 为什么必须清：上一轮验收的上传用例会在工作区里落文件，其中 `path=docs` 那一条
# 在 docs/ 不存在时会把 **docs 落成一个文件**；此时 `mkdir -p "$WS/docs"` 会直接失败，
# 于是整套夹具都造不出来，后续 /fs/* 用例整片 404 —— 看起来像产品坏了，其实是夹具没造。
rm -rf "$WS/build" "$WS/docs" "$WS/nested" "$WS/big.bin" "$WS/escape-link.txt" "$WS/escape-dir"

mkdir -p "$WS/build/outputs" "$WS/docs" "$WS/nested/deep" "$OUTSIDE"

# 二进制样本（验证 mime 映射）
head -c 300000 /dev/urandom > "$WS/build/outputs/app-debug.apk"
# 文本样本（验证下载内容逐字节）
printf 'hello from dsh remote gateway\n' > "$WS/docs/readme.txt"
# 1 MiB 样本（验证 Range 分块与拼接）
head -c 1048576 /dev/urandom > "$WS/big.bin"

# 越界防护第二重（软链逃逸）的靶子：工作区**之外**的文件
printf 'secret\n' > "$OUTSIDE/outside-secret.txt"
ln -sfn "$OUTSIDE/outside-secret.txt" "$WS/escape-link.txt"
ln -sfn "$OUTSIDE" "$WS/escape-dir"

echo "已造夹具于 $WS（越界靶子在 $OUTSIDE）"
