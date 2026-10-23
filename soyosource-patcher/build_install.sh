#!/usr/bin/env bash
# Rebuild soyo_src, sign it, install it on the phone over ADB and launch it.
# Run from ~/apkwork. First install: run `adb uninstall com.soysource` once.
set -euo pipefail

PKG=com.soysource
SRC=soyo_src
BT="${BT:-$HOME/android-sdk/build-tools/34.0.0}"   # override: BT=/path ./build_install.sh
export PATH="$BT:$PATH"

command -v zipalign >/dev/null || { echo "zipalign not found in $BT - install build-tools with sdkmanager"; exit 1; }

if [ ! -f my.keystore ]; then
  echo "Creating signing key (remember the password)..."
  keytool -genkey -v -keystore my.keystore -alias me -keyalg RSA -keysize 2048 -validity 10000
fi

java -jar apktool.jar b "$SRC" -o soyo_mod_unsigned.apk
zipalign -f -p 4 soyo_mod_unsigned.apk soyo_mod.apk
apksigner sign --ks my.keystore --ks-key-alias me --v1-signing-enabled true soyo_mod.apk

if ! adb install -r soyo_mod.apk; then
  echo
  echo "Install failed. If it says INSTALL_FAILED_UPDATE_INCOMPATIBLE, run once:"
  echo "    adb uninstall $PKG"
  echo "then re-run this script."
  exit 1
fi

adb logcat -c
adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Launched. If it crashes, run:"
echo "    adb logcat -d | grep -E 'AndroidRuntime|VFY|soysource' | tail -60"
