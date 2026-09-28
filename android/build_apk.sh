#!/bin/bash
# build_apk.sh —— Hand-rolled Android build pipeline for DshRemote (plain Java, no Gradle)
#
# aarch64 Kylin Linux: JDK is native; aapt2/zipalign are x86_64 and run via qemu.
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# Toolchain (verified present, do not modify)
T=/root/project/face/android-sdk/.tooling
JDK="$T/jdk-17.0.20.1+1"
Q="$T/qemu/usr/bin/qemu-x86_64-static"
X="$T/x86root"
AAPT2="$T/bt/android-14/aapt2"
D8JAR="$T/bt/android-14/lib/d8.jar"
APKSIGNER="$T/bt/android-14/lib/apksigner.jar"
ZIPALIGN="$T/bt/android-14/zipalign"
ANDROID_JAR="$T/platform/android-35/android.jar"

# Output paths (all under HERE/dist/)
OUT="$HERE/dist"
BUILD="$OUT/apk-build"
KEYSTORE="$OUT/debug.keystore"
APK_OUT="$OUT/DshRemote-debug.apk"

# ── Pre-flight: fail fast if any tool is missing ──
for f in "$JDK/bin/javac" "$JDK/bin/java" "$JDK/bin/keytool" "$Q" "$AAPT2" "$D8JAR" "$APKSIGNER" "$ZIPALIGN" "$ANDROID_JAR"; do
    [ -e "$f" ] || { echo "ERROR: missing tool: $f"; exit 2; }
done

# Fail fast if java/ has no .java files
JAVA_COUNT=$(find "$HERE/java" -name '*.java' 2>/dev/null | wc -l)
if [ "$JAVA_COUNT" -eq 0 ]; then
    echo "ERROR: no .java files found under $HERE/java/"
    exit 2
fi
echo "Found $JAVA_COUNT Java source files."

echo
echo "=================================================="
echo "  Step 1/7  Prepare build directories"
echo "=================================================="
mkdir -p "$OUT" "$BUILD"
rm -rf "$BUILD"/*

echo
echo "=================================================="
echo "  Step 2/7  aapt2 compile + link"
echo "=================================================="
mkdir -p "$BUILD/res"
if [ -d "$HERE/res" ]; then
    "$Q" -L "$X" "$AAPT2" compile --dir "$HERE/res" -o "$BUILD/res.zip"
    RES_ARGS=( -R "$BUILD/res.zip" )
else
    echo "  No res/ directory found; skipping resource compilation."
    RES_ARGS=()
fi

"$Q" -L "$X" "$AAPT2" link \
    -I "$ANDROID_JAR" \
    --manifest "$HERE/AndroidManifest.xml" \
    "${RES_ARGS[@]}" \
    --auto-add-overlay \
    --min-sdk-version 26 \
    --target-sdk-version 34 \
    --version-code 1 \
    --version-name 1.0 \
    -o "$BUILD/base.ap_"

echo
echo "=================================================="
echo "  Step 3/7  javac — compile Java sources"
echo "=================================================="
mkdir -p "$BUILD/classes"
# -bootclasspath makes -source 8 compile against Android framework, not JDK runtime
# -nowarn suppresses the "bootstrap classpath" warning that would otherwise fail the build
"$JDK/bin/javac" -encoding UTF-8 -source 8 -target 8 -nowarn \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$BUILD/classes" \
    $(find "$HERE/java" -name '*.java')

echo
echo "=================================================="
echo "  Step 4/7  d8 — generate classes.dex"
echo "=================================================="
mkdir -p "$BUILD/dex"
"$JDK/bin/java" -cp "$D8JAR" com.android.tools.r8.D8 \
    --lib "$ANDROID_JAR" \
    --min-api 26 \
    --release \
    --output "$BUILD/dex" \
    $(find "$BUILD/classes" -name '*.class')

echo
echo "=================================================="
echo "  Step 5/7  Assemble APK (resources.arsc + classes.dex must be STORED)"
echo "=================================================="
rm -rf "$BUILD/zip"
mkdir -p "$BUILD/zip"
( cd "$BUILD/zip" && unzip -q "$BUILD/base.ap_" )
cp "$BUILD/dex/classes.dex" "$BUILD/zip/"

# Inline python3 heredoc: write resources.arsc + classes.dex as ZIP_STORED,
# everything else as ZIP_DEFLATED.  This is the LESSONS.md hard requirement
# for Android 11+ (API 30+).
python3 - "$BUILD/zip" "$BUILD/assembled.apk" <<'PYEOF'
import os, sys, zipfile
srcdir, out = sys.argv[1], sys.argv[2]
STORE = {"resources.arsc", "classes.dex"}
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for root, _dirs, files in os.walk(srcdir):
        for f in sorted(files):
            p = os.path.join(root, f)
            arc = os.path.relpath(p, srcdir).replace(os.sep, "/")
            if arc in STORE:
                z.write(p, arc, compress_type=zipfile.ZIP_STORED)
            else:
                z.write(p, arc, compress_type=zipfile.ZIP_DEFLATED)
print("  assembled:", out)
PYEOF

echo
echo "=================================================="
echo "  Step 6/7  zipalign — 4-byte alignment"
echo "=================================================="
"$Q" -L "$X" "$ZIPALIGN" -f -p 4 \
    "$BUILD/assembled.apk" "$BUILD/unsigned.apk"
echo "-- zipalign self-check (tail) --"
"$Q" -L "$X" "$ZIPALIGN" -c -v 4 "$BUILD/unsigned.apk" | tail -3

echo
echo "=================================================="
echo "  Step 7/7  Sign APK (debug keystore, v1+v2+v3)"
echo "=================================================="
if [ ! -f "$KEYSTORE" ]; then
    echo "  Generating debug keystore..."
    "$JDK/bin/keytool" -genkeypair -keystore "$KEYSTORE" \
        -storepass android -keypass android \
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 \
        -dname "CN=DshRemote Debug,OU=Dev,O=Dsh,L=Beijing,ST=Beijing,C=CN" \
        >/dev/null 2>&1
fi
rm -f "$APK_OUT"
"$JDK/bin/java" -jar "$APKSIGNER" sign \
    --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --ks-key-alias androiddebugkey \
    --v1-signing-enabled true \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --min-sdk-version 26 \
    --out "$APK_OUT" \
    "$BUILD/unsigned.apk"

echo
echo "-- apksigner verify --"
"$JDK/bin/java" -jar "$APKSIGNER" verify --print-certs "$APK_OUT" | head -5

echo
echo "=================================================="
echo "  Verify APK structure"
echo "=================================================="
python3 "$HERE/tools/verify_apk.py" "$APK_OUT"

echo
echo "=================================================="
echo "  aapt2 dump badging"
echo "=================================================="
"$Q" -L "$X" "$AAPT2" dump badging "$APK_OUT" 2>/dev/null || echo "  (aapt2 dump badging skipped)"

echo
echo "=================================================="
APK_ABS="$(cd "$(dirname "$APK_OUT")" && pwd)/$(basename "$APK_OUT")"
echo "APK: $APK_ABS"
echo "=================================================="
echo "Size: $(du -h "$APK_OUT" | cut -f1)"
