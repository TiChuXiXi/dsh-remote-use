#!/bin/bash
# run_verify.sh —— 在桌面 JVM 上编译并运行 net/ 层的实网验证harness
#
# 用法:
#   bash verify/run_verify.sh --url http://127.0.0.1:3081 --user admin --pass 'secret'
#   bash verify/run_verify.sh http://127.0.0.1:3081 admin secret          # 位置参数亦可
#
# 说明:
#   - 只用 JDK 17 的 javac/java，外加 net/ + verify/ 的纯 Java 源码；
#     整个过程不触碰 android.jar，因此能证明 net/ 不依赖任何 Android API。
#   - 编译使用 `--release 8`：既产出 Java 8 字节码，也强制校验所有用到的
#     JDK API 在 Java 8 就存在（防止误用 Java 9+ 的 API）。
#   - 退出码: 0 = 全部通过; 1 = 有步骤失败; 2 = 网关不可达（网关尚未实现时属预期）。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
T="/root/project/face/android-sdk/.tooling"
JDK="$(ls -d "$T"/jdk-17* 2>/dev/null | head -1)"

if [ -z "${JDK:-}" ] || [ ! -x "$JDK/bin/javac" ]; then
    echo "找不到 JDK: $T/jdk-17*" >&2
    exit 3
fi

NET_SRC="$HERE/java/com/dsh/remote/net"
if [ ! -d "$NET_SRC" ] || [ -z "$(find "$NET_SRC" -name '*.java' -print -quit)" ]; then
    echo "找不到 net/ 源码: $NET_SRC" >&2
    exit 3
fi

BUILD="$HERE/.verify-build"
rm -rf "$BUILD"
mkdir -p "$BUILD"

echo "== 编译 net/ + verify/（--release 8，仅 JDK，不含 android.jar）=="
"$JDK/bin/javac" -encoding UTF-8 --release 8 -nowarn -d "$BUILD" \
    $(find "$NET_SRC" "$HERE/verify" -name '*.java')
echo "   编译通过 -> $BUILD"
echo

cd "$HERE"
mkdir -p verify-out
exec "$JDK/bin/java" -cp "$BUILD" com.dsh.remote.verify.VerifyMain "$@"
