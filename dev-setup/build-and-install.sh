#!/usr/bin/env bash
# Build OmnioTV (debug) and install it onto the running Android TV emulator.
# Usage:  bash dev-setup/build-and-install.sh
set -euo pipefail

# --- Resolve env if needed ---------------------------------------------------
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if   [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  fi
fi
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$PATH"

if [[ -z "${JAVA_HOME:-}" && -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
fi

# --- Project root (this script lives in dev-setup/) -------------------------
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_ROOT"

# --- Wait for an emulator/device --------------------------------------------
if ! adb get-state >/dev/null 2>&1; then
  echo "No device/emulator detected."
  echo "Start one first:  bash dev-setup/launch-tv-emulator.sh"
  exit 1
fi
echo "==> Waiting for device to be fully booted …"
adb wait-for-device
# Wait until boot completed flag is set
while [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" != "1" ]]; do
  sleep 2
done
echo "    device ready."

# --- Build & install --------------------------------------------------------
echo "==> Running ./gradlew installDebug"
./gradlew :app-tv:installDebug

# --- Launch the app ---------------------------------------------------------
echo "==> Launching OmnioTV"
adb shell monkey -p com.omnio.tv -c android.intent.category.LAUNCHER 1 >/dev/null
echo "Done. App should now be running on the TV emulator."
