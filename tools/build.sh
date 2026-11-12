#!/bin/zsh
# usage: ANDROID_SERIAL=emulator-5556 tools/build.sh
# Build the debug APK, install it on that device and start it again. Prints only the
# compiler's errors, or "Success".
[ -n "$ANDROID_SERIAL" ] || { echo "set ANDROID_SERIAL: a real phone may be plugged in"; exit 2; }
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 ANDROID_HOME=/opt/homebrew/share/android-commandlinetools
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
cd "${0:A:h}/../app"
out=$(./gradlew -q assembleDebug 2>&1 | grep -E "^e: |FAILED|error:" | sed "s|file://$PWD/app/src/main/java/ca/lampandlight/||")
if [ -n "$out" ]; then echo "$out" | head -40; exit 1; fi
$ADB install -r app/build/outputs/apk/debug/app-debug.apk 2>&1 | tail -1
$ADB shell am force-stop ca.lampandlight
$ADB shell am start -n ca.lampandlight/.MainActivity >/dev/null
