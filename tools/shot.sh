#!/bin/zsh
# usage: ANDROID_SERIAL=emulator-5556 tools/shot.sh <dir> <name>
# Writes <dir>/<name>.png, a screenshot scaled to 1200 px tall, and prints its path.
# <dir> is a scratch folder, not the repository.
[ -n "$ANDROID_SERIAL" ] || { echo "set ANDROID_SERIAL: a real phone may be plugged in"; exit 2; }
[ $# -eq 2 ] || { echo "usage: shot.sh <dir> <name>"; exit 2; }
ADB=/opt/homebrew/share/android-commandlinetools/platform-tools/adb
mkdir -p "$1"
$ADB exec-out screencap -p > "$1/$2.full.png"
sips -Z 1200 "$1/$2.full.png" --out "$1/$2.png" >/dev/null 2>&1
echo "$1/$2.png"
