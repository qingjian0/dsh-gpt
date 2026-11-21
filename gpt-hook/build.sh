#!/system/bin/sh
# 一键构建 LSPosed 模块 APK（无需 gradle / 联网）
set -e
R=$(cd "$(dirname "$0")" && pwd)
ENVE="/data/user/0/dsh.dsh.mobile/files/engine"
[ -n "$PREFIX" ] && [ -d "$PREFIX/extensions" ] && ENVE="$PREFIX"
JDK=$(ls -d "$ENVE"/extensions/openjdk-17/lib/jvm/*/bin 2>/dev/null | head -1)
BT="$ENVE/extensions/android-buildtools/bin"
JAVAC="$JDK/javac"; export PATH="$JDK:$PATH"; JAVA="$JDK/java"; KEYTOOL="$JDK/keytool"
AAPT="$BT/aapt"; D8="$BT/d8"; ZIPALIGN="$BT/zipalign"; APKSIGNER="$BT/apksigner"
AJ="$DSH_HOME/android.jar"; [ -f "$AJ" ] || AJ="/data/user/0/dsh.dsh.mobile/files/dsh-home/android.jar"
export LD_LIBRARY_PATH="$ENVE/extensions/openjdk-17/lib:$ENVE/extensions/android-buildtools/lib${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"
FBP=/system/framework/framework-res.apk
OUT="$R/build"
rm -rf "$OUT"; mkdir -p "$OUT/stub" "$OUT/sout" "$OUT/gen" "$OUT/dex"

echo "[1/7] stub"
"$JAVAC" -nowarn -source 8 -target 8 -cp "$AJ" -d "$OUT/stub" $(find "$R/toolchain/xposed-stub/src" -name '*.java') 2>&1 | grep -v "bootstrap class path" || true
[ -d "$OUT/stub/de/robv" ] || { echo "STUB COMPILE FAILED"; exit 1; }
"$JDK/jar" cf "$OUT/xposed-api.jar" -C "$OUT/stub" .

echo "[2/7] aapt gen R + resources"
"$AAPT" package -f -m -J "$OUT/gen" -M "$R/app/src/main/AndroidManifest.xml" \
  -S "$R/app/src/main/res" -A "$R/app/src/main/assets" -I "$FBP"

echo "[3/7] compile module"
"$JAVAC" -nowarn -source 8 -target 8 -encoding UTF-8 \
  -cp "$AJ:$OUT/xposed-api.jar" -d "$OUT/sout" \
  $(find "$R/app/src/main/java" -name '*.java') $(find "$OUT/gen" -name '*.java') 2>&1 \
  >&1 | grep -vE "bootstrap class path|source value 8|target value 8|To suppress warnings" || true
[ -d "$OUT/sout/com/gpt/hook" ] || { echo "MODULE COMPILE FAILED"; exit 1; }

echo "[4/7] dex"
"$JAVA" -cp "$BT/../lib/r8.jar:$BT" com.android.tools.r8.D8 --release --min-api 26 \
  --lib "$AJ" --output "$OUT/dex" $(find "$OUT/sout" -name '*.class') 2>/dev/null || \
"$D8" --release --min-api 26 --lib "$AJ" --output "$OUT/dex" $(find "$OUT/sout" -name '*.class')
ls -l "$OUT/dex/classes.dex"

echo "[5/7] package"
"$AAPT" package -f -M "$R/app/src/main/AndroidManifest.xml" --version-code 20 --version-name 2.0 \
  -S "$R/app/src/main/res" -A "$R/app/src/main/assets" -I "$FBP" -F "$OUT/app-unsigned.apk"
(cd "$OUT/dex" && "$AAPT" add "$OUT/app-unsigned.apk" classes.dex)

echo "[6/7] zipalign"
"$ZIPALIGN" -f 4 "$OUT/app-unsigned.apk" "$OUT/app-aligned.apk"

echo "[7/7] sign"
if [ ! -f "$R/keystore.jks" ]; then
  "$KEYTOOL" -genkeypair -keystore "$R/keystore.jks" -alias gpt -keyalg RSA -keysize 2048 \
    -validity 10000 -storepass gpt123456 -keypass gpt123456 \
    -dname "CN=GPT Hook Mode, OU=local, O=local, L=local, ST=local, C=CN" >/dev/null 2>&1
fi
"$APKSIGNER" sign --ks "$R/keystore.jks" --ks-pass pass:gpt123456 --key-pass pass:gpt123456 \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out "$OUT/gpt-hook.apk" "$OUT/app-aligned.apk"
"$APKSIGNER" verify "$OUT/gpt-hook.apk" || true
echo "OK -> $OUT/gpt-hook.apk"
