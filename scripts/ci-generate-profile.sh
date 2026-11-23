#!/bin/sh
#
# Refreshes the baseline profile of :app on the connected TV emulator, then
# union-merges the committed profile files with the fresh collection so no
# accumulated rule is lost.
#
# Runs on CI inside android-emulator-runner (android-release.yml), where the
# emulator is already booted; the action runs each line of its `script` input
# as a separate `sh -c`, so anything non-trivial lives in this file.
#
set -e

profile_dir=app/src/main/generated/baselineProfiles
backup_dir="${RUNNER_TEMP:-/tmp}/committed-baseline-profiles"

# The journeys need a woken, unlocked device - otherwise the app never gets
# the focus and every journey degrades (see baselineprofile/README.md).
adb shell svc power stayon true
adb shell input keyevent KEYCODE_WAKEUP || true
adb shell input keyevent 82 || true

# Snapshot the committed profiles before the generation flow touches them:
# the plugin replaces the files with the fresh collection (and has been
# observed to delete startup-prof.txt) rather than merging into them.
mkdir -p "$backup_dir"
cp "$profile_dir/baseline-prof.txt" "$backup_dir/"
cp "$profile_dir/startup-prof.txt" "$backup_dir/"

./gradlew :app:generateBaselineProfile --stacktrace

adb emu kill || true

# Union-merge the committed and the fresh rules for every profile file. The
# rule format is one rule per line with leading flags, so a line union is a
# valid superset: AGP drops rules that no longer match the dex when it
# compiles the profile into the build. A file the flow deleted is restored
# from the snapshot (the committed startup profile stays authoritative).
for name in baseline-prof.txt startup-prof.txt; do
  fresh="$profile_dir/$name"
  committed="$backup_dir/$name"
  if [ -s "$fresh" ]; then
    cat "$committed" "$fresh" | awk '!seen[$0]++' > "$fresh.merged"
    mv "$fresh.merged" "$fresh"
  else
    cp "$committed" "$fresh"
    echo "::notice::$name was removed by the generation flow; restored the committed version."
  fi
done

changed=$(git diff --numstat -- "$profile_dir" | awk '{print $3" +"$1" -"$2}' | tr '\n' ' ')
if [ -z "$changed" ]; then
  echo "::notice::The committed baseline profile already matches this build - nothing was refreshed."
else
  echo "Refreshed baseline profile files: $changed"
fi
