#!/bin/sh
#
# Refreshes the baseline profile of :app on the connected TV emulator.
# Runs on CI inside android-emulator-runner (android-release.yml), where the
# emulator is already booted; the action runs each line of its `script` input
# as a separate `sh -c`, so anything non-trivial lives in this file instead.
#
set -e

# The journeys need a woken, unlocked device - otherwise the app never gets
# the focus and every journey degrades (see baselineprofile/README.md).
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true

gradle_args=":app:generateBaselineProfile -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.targetPackageName=com.nuviodebug.com"
if [ -n "${BASELINE_ADDON_MANIFEST_URL:-}" ]; then
  gradle_args="$gradle_args -Pandroid.testInstrumentationRunnerArguments.addonManifestUrl=${BASELINE_ADDON_MANIFEST_URL}"
else
  echo "::warning::BASELINE_ADDON_MANIFEST_URL is not set. The player journey degrades and its path rules are not collected."
fi

./gradlew $gradle_args --stacktrace

adb emu kill || true

changed=$(git diff --name-only -- app/src/main/generated/baselineProfiles | tr '\n' ' ')
if [ -z "$changed" ]; then
  echo "::notice::The committed baseline profile already matches this build - nothing was refreshed."
else
  echo "Refreshed baseline profile files: $changed"
fi
