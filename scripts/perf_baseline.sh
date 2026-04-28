#!/usr/bin/env bash
set -euo pipefail

usage() {
  cat <<USAGE
Usage: $0 <generate-profile|benchmark|release-with-profile>

Commands:
  generate-profile     Generate app baseline profile on connected device
  benchmark            Run startup + navigation macrobenchmarks on connected device
  release-with-profile Build release APK/AAB with merged baseline profile artifacts
USAGE
}

cmd="${1:-}"

case "$cmd" in
  generate-profile)
    ./gradlew \
      :benchmark:connectedNonMinifiedReleaseAndroidTest \
      :benchmark:collectNonMinifiedReleaseBaselineProfile \
      -Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.tv.benchmark.NuvioBaselineProfile
    ;;
  benchmark)
    ./gradlew \
      :benchmark:connectedBenchmarkReleaseAndroidTest \
      -Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.tv.benchmark.NuvioStartupNavBenchmark \
      -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR
    ;;
  release-with-profile)
    ./gradlew :app-tv:assembleRelease :app-tv:bundleRelease
    ;;
  *)
    usage
    exit 1
    ;;
esac
