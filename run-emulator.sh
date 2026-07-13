#!/usr/bin/env bash
# Builds, starts an Android emulator when needed, installs Instasave, and opens it.
# Usage: ./run-emulator.sh [avd_name]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDK_DIR="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-$HOME/Android/Sdk}}"
ADB="$SDK_DIR/platform-tools/adb"
EMULATOR="$SDK_DIR/emulator/emulator"
AVD_NAME="${1:-Pixel_5}"
APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"

if [[ ! -x "$ADB" || ! -x "$EMULATOR" ]]; then
  echo "Android SDK not found at: $SDK_DIR" >&2
  echo "Set ANDROID_SDK_ROOT (or ANDROID_HOME) and try again." >&2
  exit 1
fi

cd "$ROOT_DIR"
./gradlew :app:assembleDebug --offline --no-daemon --console=plain

if ! "$ADB" get-state 2>/dev/null | grep -qx "device"; then
  echo "Starting emulator $AVD_NAME..."
  nohup "$EMULATOR" -avd "$AVD_NAME" >"${TMPDIR:-/tmp}/instasave-emulator.log" 2>&1 < /dev/null &
  "$ADB" wait-for-device
  for _ in {1..90}; do
    if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; then
      break
    fi
    sleep 2
  done
  if [[ "$("$ADB" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; then
    echo "The emulator did not finish booting. Log: ${TMPDIR:-/tmp}/instasave-emulator.log" >&2
    exit 1
  fi
fi

"$ADB" install -r "$APK"
"$ADB" shell am force-stop app.instasave
"$ADB" shell monkey -p app.instasave 1 > /dev/null
echo "Instasave is open in the emulator."
