#!/usr/bin/env bash
# Installs the APK on a running Android emulator, starts it in self-test mode (one real download
# through the real UI bridge) and prints what happened. Exit code 0 only if the download finished.
APK=${1:-GigaGet.apk}
PKG=app.gigaget.client

adb wait-for-device
adb shell getprop ro.build.version.release
adb install -r "$APK" || { echo "INSTALL FAILED"; exit 1; }
adb logcat -c

COMP=$(adb shell cmd package resolve-activity --brief -c android.intent.category.LAUNCHER "$PKG" | tail -n1 | tr -d '\r')
echo "launching $COMP"
adb shell am start -W -n "$COMP" --ez giga_selftest true

for i in $(seq 1 72); do
  sleep 5
  if adb logcat -d | grep -qE "GGTEST (DONE|FAIL)"; then break; fi
  if [ -z "$(adb shell pidof "$PKG" | tr -d '\r')" ]; then echo "APP PROCESS GONE"; break; fi
done

echo "=========== RESULT LOG ==========="
adb logcat -d -v time | grep -E "GGTEST|GigaGet|AndroidRuntime|FATAL|Force finishing|youtubedl|UnsatisfiedLink|NoClassDef|tauri|RustStdoutStderr" | tail -200

if adb logcat -d | grep -q "GGTEST DONE"; then echo "SELFTEST PASSED"; exit 0; fi
echo "SELFTEST FAILED"; exit 1
