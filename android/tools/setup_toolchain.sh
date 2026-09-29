#!/bin/bash
# tools/setup_toolchain.sh —— 重建 Gradle/Android SDK 工具链（android/ 的 Gradle 构建用）
#
# 为什么需要这个脚本：本机 `/` 挂载为只读，`/opt`、`/usr/local`、`~/.gradle`、`~/.android`
# 全部不可写，因此工具链只能放在仓库内的 `.toolchain/`（已被 .gitignore 排除）。
# 换机器或删掉 .toolchain/ 之后，跑这个脚本即可重建。
#
#   bash tools/setup_toolchain.sh
#
# 它做的事：
#   1. 下载并解包 Gradle 8.7 到 .toolchain/gradle-8.7/
#   2. 用本机已有的 x86_64 build-tools 拼出 .toolchain/android-sdk/，
#      并把 aapt2 / zipalign 换成 qemu-x86_64-static 包装脚本（宿主是 aarch64）
#   3. 软链 JDK 17
#
# 前置：/root/project/face/android-sdk/.tooling/ 已存在（JDK 17、build-tools、android-35、
# qemu 静态模拟器）。Gradle 构建不下载任何 SDK 组件，除 platform-tools 外也不依赖网络。
set -euo pipefail

HERE="$(cd "$(dirname "$0")/.." && pwd)"
TC="$HERE/../.toolchain"
T=/root/project/face/android-sdk/.tooling
GRADLE_VERSION=8.7
GRADLE_SHA256=544c35d6bd849ae8a5ed0bcea39ba677dc40f49df7d1835561582da2009b961d

echo "== 0/4 检查前置工具链 =="
for f in "$T/jdk-17.0.20.1+1/bin/javac" "$T/bt/android-14/aapt2" "$T/bt/android-14/zipalign" \
         "$T/qemu/usr/bin/qemu-x86_64-static" "$T/x86root" "$T/platform/android-35/android.jar"; do
    [ -e "$f" ] || { echo "缺少: $f" >&2; exit 2; }
done
echo "   OK ($T)"

echo "== 1/4  Gradle $GRADLE_VERSION =="
mkdir -p "$TC/dl"
if [ ! -x "$TC/gradle-$GRADLE_VERSION/bin/gradle" ]; then
    if [ ! -f "$TC/dl/gradle-$GRADLE_VERSION-bin.zip" ]; then
        curl -fsSL --retry 3 -o "$TC/dl/gradle-$GRADLE_VERSION-bin.zip" \
            "https://services.gradle.org/distributions/gradle-$GRADLE_VERSION-bin.zip"
    fi
    echo "$GRADLE_SHA256  $TC/dl/gradle-$GRADLE_VERSION-bin.zip" | sha256sum -c -
    unzip -q -o "$TC/dl/gradle-$GRADLE_VERSION-bin.zip" -d "$TC"
fi
echo "   $(JAVA_HOME="$T/jdk-17.0.20.1+1" "$TC/gradle-$GRADLE_VERSION/bin/gradle" --version \
        | grep '^Gradle ' || true)"

echo "== 2/4  Android SDK（platform 35 + build-tools 34.0.0）=="
SDK="$TC/android-sdk"
mkdir -p "$SDK/platforms" "$SDK/build-tools" "$SDK/licenses"
ln -sfn "$T/platform/android-35" "$SDK/platforms/android-35"
if [ ! -x "$SDK/build-tools/34.0.0/aapt2" ] \
   || ! head -1 "$SDK/build-tools/34.0.0/aapt2" 2>/dev/null | grep -q '^#!/bin/bash'; then
    rm -rf "$SDK/build-tools/34.0.0"
    cp -a "$T/bt/android-14" "$SDK/build-tools/34.0.0"
    # aapt2 / zipalign 是 x86_64 ELF，aarch64 宿主上必须走 qemu；d8/apksigner 是 Java，无需包装
    for b in aapt2 zipalign; do
        rm -f "$SDK/build-tools/34.0.0/$b"
        cat > "$SDK/build-tools/34.0.0/$b" <<EOF
#!/bin/bash
# aarch64 host: the real $b is an x86_64 ELF; run it under qemu user emulation with the
# bundled x86_64 sysroot, exactly as android/build_apk.sh does.
exec "$T/qemu/usr/bin/qemu-x86_64-static" -L "$T/x86root" "$T/bt/android-14/$b" "\$@"
EOF
        chmod +x "$SDK/build-tools/34.0.0/$b"
    done
    printf 'Pkg.UserSrc=false\nPkg.Revision=34.0.0\n' > "$SDK/build-tools/34.0.0/source.properties"
fi
for l in android-sdk-license android-sdk-preview-license; do
    printf '\n24333f8a63b6825ea9c5514f83c2829b004d1fee\n8933bad161af4178b1185d1a37fbf41ea5269c55\nd56f5187479451eabf01fb78af6dfcb131a6481e\n' > "$SDK/licenses/$l"
done
echo "   aapt2: $("$SDK/build-tools/34.0.0/aapt2" version 2>&1 | head -1)"
echo "   zipalign: $("$SDK/build-tools/34.0.0/zipalign" 2>&1 | head -1)"

echo "== 3/4  JDK 17 软链 =="
ln -sfn "$T/jdk-17.0.20.1+1" "$TC/jdk-17"
"$TC/jdk-17/bin/java" -version 2>&1 | head -1

echo "== 4/4  AGP 需要可写的 HOME 目录 =="
mkdir -p "$TC/gradle-home" "$TC/android-home" "$TC/robolectric-home"
echo "   GRADLE_USER_HOME=$TC/gradle-home"
echo "   ANDROID_USER_HOME=$TC/android-home"

echo
echo "完成。构建：cd $HERE && ./gradlew :app:assembleDebug"
echo "测试：  cd $HERE && ./gradlew :app:testDebugUnitTest"
