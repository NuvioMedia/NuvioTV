#!/usr/bin/env bash
# =============================================================================
# OmnioTV — One-shot dev environment setup for macOS (Apple Silicon)
# -----------------------------------------------------------------------------
# Installs everything needed to build OmnioTV and run it on an Android TV
# emulator on your Mac. Safe to re-run — every step is idempotent.
#
# What it does:
#   1. Verifies Homebrew is installed and up to date
#   2. Installs Eclipse Temurin JDK 17 (required by AGP 8.13)
#   3. Installs Android Studio (cask)
#   4. Installs Android command-line tools (sdkmanager + avdmanager)
#   5. Persists ANDROID_HOME and PATH entries in ~/.zshrc
#   6. Accepts the Android SDK licenses
#   7. Installs:
#        - platform-tools (adb)
#        - platforms;android-36         (compileSdk = 36)
#        - build-tools;36.0.0
#        - emulator
#        - system-images;android-34;android-tv;arm64-v8a   (Android TV API 34)
#   8. Creates an Android TV AVD called "OmnioTV_TV_API34"
#
# Run:    bash dev-setup/setup-omniotv-dev.sh
# =============================================================================

set -euo pipefail

# ---------- pretty printing ---------------------------------------------------
RED='\033[0;31m'; GRN='\033[0;32m'; YLW='\033[1;33m'; BLU='\033[0;34m'; NC='\033[0m'
log()  { printf "${BLU}==>${NC} %s\n" "$*"; }
ok()   { printf "${GRN}✓${NC} %s\n" "$*"; }
warn() { printf "${YLW}!${NC} %s\n" "$*"; }
die()  { printf "${RED}✗${NC} %s\n" "$*" >&2; exit 1; }

# ---------- preflight ---------------------------------------------------------
log "Pre-flight checks"

if [[ "$(uname)" != "Darwin" ]]; then
  die "This script is for macOS only."
fi

if [[ "$(uname -m)" != "arm64" ]]; then
  warn "This script is tuned for Apple Silicon (arm64). You appear to be on $(uname -m)."
  warn "It will still work, but the emulator system image will be slow."
fi

if ! command -v brew >/dev/null 2>&1; then
  die "Homebrew is not installed. Install it from https://brew.sh and re-run."
fi
ok "Homebrew detected: $(brew --version | head -n1)"

# ---------- 1. Update Homebrew ------------------------------------------------
log "Updating Homebrew"
brew update >/dev/null
ok "Homebrew up to date"

# ---------- 2. JDK 17 ---------------------------------------------------------
log "Installing Eclipse Temurin JDK 17 (required by AGP 8.13)"
if brew list --cask temurin@17 >/dev/null 2>&1; then
  ok "temurin@17 already installed"
else
  brew install --cask temurin@17
  ok "temurin@17 installed"
fi

# ---------- 3. Android Studio -------------------------------------------------
log "Installing Android Studio"
if brew list --cask android-studio >/dev/null 2>&1; then
  ok "Android Studio already installed"
else
  brew install --cask android-studio
  ok "Android Studio installed"
fi

# ---------- 4. Android command-line tools -------------------------------------
log "Installing Android command-line tools"
if brew list --cask android-commandlinetools >/dev/null 2>&1; then
  ok "android-commandlinetools already installed"
else
  brew install --cask android-commandlinetools
  ok "android-commandlinetools installed"
fi

# ---------- 5. Environment variables in ~/.zshrc ------------------------------
log "Configuring ANDROID_HOME and PATH in ~/.zshrc"

# Brew puts cmdline-tools in this share dir on Apple Silicon
ANDROID_SDK_ROOT="/opt/homebrew/share/android-commandlinetools"
if [[ ! -d "$ANDROID_SDK_ROOT" ]]; then
  # Intel Mac fallback
  ANDROID_SDK_ROOT="/usr/local/share/android-commandlinetools"
fi
[[ -d "$ANDROID_SDK_ROOT" ]] || die "Could not locate android-commandlinetools install dir."

ZRC="$HOME/.zshrc"
touch "$ZRC"

# Idempotent insert — only add the block if our marker isn't already present
if ! grep -q "# >>> OmnioTV dev env >>>" "$ZRC"; then
  cat >> "$ZRC" <<EOF

# >>> OmnioTV dev env >>>
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="\$ANDROID_HOME"
export PATH="\$ANDROID_HOME/platform-tools:\$PATH"
export PATH="\$ANDROID_HOME/emulator:\$PATH"
export PATH="\$ANDROID_HOME/cmdline-tools/latest/bin:\$PATH"
# Use Temurin 17 for Gradle / AGP
if [[ -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
fi
# <<< OmnioTV dev env <<<
EOF
  ok "Added env block to $ZRC"
else
  ok "Env block already present in $ZRC (skipped)"
fi

# Export for the current shell session as well
export ANDROID_HOME="$ANDROID_SDK_ROOT"
export ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
if [[ -d "/Library/Java/JavaVirtualMachines/temurin-17.jdk" ]]; then
  export JAVA_HOME="/Library/Java/JavaVirtualMachines/temurin-17.jdk/Contents/Home"
fi

# Locate sdkmanager / avdmanager (brew puts them under cmdline-tools/latest/bin)
SDKMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$ANDROID_HOME/cmdline-tools/latest/bin/avdmanager"
[[ -x "$SDKMANAGER" ]] || die "sdkmanager not found at $SDKMANAGER"
[[ -x "$AVDMANAGER" ]] || die "avdmanager not found at $AVDMANAGER"
ok "Tools located: sdkmanager, avdmanager"

# ---------- 6. Accept licenses ------------------------------------------------
log "Accepting Android SDK licenses"
yes | "$SDKMANAGER" --licenses >/dev/null 2>&1 || true
ok "Licenses accepted"

# ---------- 7. Install SDK packages ------------------------------------------
log "Installing SDK packages (this can take 5–15 minutes)"
"$SDKMANAGER" \
  "platform-tools" \
  "platforms;android-36" \
  "build-tools;36.0.0" \
  "emulator" \
  "system-images;android-34;android-tv;arm64-v8a"
ok "SDK packages installed"

# ---------- 8. Create the Android TV AVD --------------------------------------
AVD_NAME="OmnioTV_TV_API34"
log "Creating AVD: $AVD_NAME"
if "$AVDMANAGER" list avd | grep -q "Name: $AVD_NAME"; then
  ok "AVD '$AVD_NAME' already exists (skipped)"
else
  echo "no" | "$AVDMANAGER" create avd \
    --name "$AVD_NAME" \
    --package "system-images;android-34;android-tv;arm64-v8a" \
    --device "tv_1080p" \
    --force
  ok "AVD '$AVD_NAME' created"
fi

# ---------- 9. Done -----------------------------------------------------------
cat <<EOF

${GRN}===========================================================${NC}
  ✓ OmnioTV development environment is ready.
${GRN}===========================================================${NC}

Next steps (open a NEW terminal so the env vars are loaded):

  1. Start the Android TV emulator:
       bash dev-setup/launch-tv-emulator.sh

  2. In another terminal, build & install OmnioTV onto the emulator:
       bash dev-setup/build-and-install.sh

  3. Or open the project in Android Studio:
       open -a "Android Studio" "$(pwd)"

Notes:
  • AVD name:        $AVD_NAME
  • Android target:  Android TV — API 34 (ARM64, native on Apple Silicon)
  • compileSdk:      android-36
  • JDK:             Temurin 17 (\$JAVA_HOME)

EOF
