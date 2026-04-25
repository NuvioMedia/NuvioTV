#!/usr/bin/env bash
# Start the OmnioTV Android TV emulator.
# Usage:  bash dev-setup/launch-tv-emulator.sh
set -euo pipefail

AVD_NAME="${AVD_NAME:-OmnioTV_TV_API34}"

# Resolve ANDROID_HOME if the parent shell hasn't sourced ~/.zshrc yet
if [[ -z "${ANDROID_HOME:-}" ]]; then
  if   [[ -d "/opt/homebrew/share/android-commandlinetools" ]]; then
    export ANDROID_HOME="/opt/homebrew/share/android-commandlinetools"
  elif [[ -d "$HOME/Library/Android/sdk" ]]; then
    export ANDROID_HOME="$HOME/Library/Android/sdk"
  else
    echo "ANDROID_HOME not set and no SDK found. Run setup-omniotv-dev.sh first." >&2
    exit 1
  fi
fi

EMULATOR="$ANDROID_HOME/emulator/emulator"
[[ -x "$EMULATOR" ]] || { echo "emulator binary missing at $EMULATOR" >&2; exit 1; }

# Verify AVD exists
if ! "$EMULATOR" -list-avds | grep -qx "$AVD_NAME"; then
  echo "AVD '$AVD_NAME' not found. Available AVDs:" >&2
  "$EMULATOR" -list-avds >&2
  echo "Re-run dev-setup/setup-omniotv-dev.sh to create it." >&2
  exit 1
fi

echo "Booting $AVD_NAME …  (close the emulator window to stop)"
exec "$EMULATOR" -avd "$AVD_NAME" -no-snapshot-save -gpu auto
