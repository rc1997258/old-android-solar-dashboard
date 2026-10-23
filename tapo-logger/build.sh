#!/usr/bin/env bash
# Build TapoLogger.apk without Gradle: aapt + javac + d8 (or dx) + zipalign + apksigner.
#
# On Rocky WSL (same toolchain as the Soyosource project), one-time:
#   ~/android-sdk/cmdline-tools/bin/sdkmanager --sdk_root=$HOME/android-sdk "platforms;android-23"
# then:   ./build.sh            (signs with ~/apkwork/my.keystore if present, else creates debug.keystore)
set -euo pipefail
cd "$(dirname "$0")"

SDK="${ANDROID_SDK:-$HOME/android-sdk}"
BT="${BT:-$SDK/build-tools/34.0.0}"
export PATH="$BT:$PATH"

# android.jar: SDK platform 23 (any 19+ works), or Ubuntu's android-sdk-platform-23 package
for j in "$SDK/platforms/android-23/android.jar" "$SDK"/platforms/android-*/android.jar \
         /usr/lib/android-sdk/platforms/android-23/android.jar; do
  [ -f "$j" ] && { ANDROID_JAR="$j"; break; }
done
: "${ANDROID_JAR:?android.jar not found - run: sdkmanager --sdk_root=\$HOME/android-sdk \"platforms;android-23\"}"
echo "android.jar: $ANDROID_JAR"

rm -rf build && mkdir -p build/gen build/obj build/dex

# 1. resources -> R.java
aapt package -f -m -J build/gen -M AndroidManifest.xml -S res -I "$ANDROID_JAR"

# 2. compile (Java 8 bytecode, no lambdas, for old Dalvik)
javac -nowarn -source 8 -target 8 -bootclasspath "$ANDROID_JAR" -encoding UTF-8 -d build/obj \
      $(find src build/gen -name '*.java') 2>&1 | grep -v -E '^(warning|Note|[0-9]+ warning)' || true
[ -f build/obj/com/diy/tapologger/MainActivity.class ] || { echo "javac failed"; exit 1; }

# 3. dex
if command -v d8 >/dev/null; then
  d8 --release --min-api 14 --lib "$ANDROID_JAR" --output build/dex $(find build/obj -name '*.class')
elif command -v dalvik-exchange >/dev/null; then
  dalvik-exchange --dex --min-sdk-version=14 --output=build/dex/classes.dex build/obj
else
  dx --dex --min-sdk-version=14 --output=build/dex/classes.dex build/obj
fi

# 4. package + add dex
aapt package -f -M AndroidManifest.xml -S res -I "$ANDROID_JAR" -F build/unaligned.apk
( cd build/dex && aapt add ../unaligned.apk classes.dex >/dev/null )

# 5. align + sign (v1 signature for KitKat)
zipalign -f -p 4 build/unaligned.apk build/aligned.apk
KS="${KEYSTORE:-}"; ALIAS="${KEY_ALIAS:-}"
if [ -z "$KS" ] && [ -f "$HOME/apkwork/my.keystore" ]; then KS="$HOME/apkwork/my.keystore"; ALIAS=me; fi
if [ -z "$KS" ]; then
  KS=debug.keystore; ALIAS=debug
  [ -f "$KS" ] || keytool -genkeypair -keystore "$KS" -storepass android -keypass android -alias debug \
      -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=TapoLogger debug" >/dev/null 2>&1
  apksigner sign --ks "$KS" --ks-pass pass:android --ks-key-alias "$ALIAS" \
      --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 14 --out TapoLogger.apk build/aligned.apk
else
  apksigner sign --ks "$KS" --ks-key-alias "$ALIAS" \
      --v1-signing-enabled true --v2-signing-enabled true --min-sdk-version 14 --out TapoLogger.apk build/aligned.apk
fi
echo "Built: $(pwd)/TapoLogger.apk"
