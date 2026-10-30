#!/usr/bin/env bash
#
# Performance gate for the NuvioTV TV app.
#
# The benchmark code lives in the :baselineprofile module (package com.nuvio.tv.baselineprofile):
#   * BaselineProfileGenerator  -> collects the baseline profile
#   * NuvioFrameMetricsBenchmark -> frame timing guard rails (frameDurationCpuMs / frameOverrunMs)
#   * NuvioStartupBenchmark      -> startup guard rail (timeToInitialDisplayMs)
#
# The script is bash and is run either from Git Bash or from a POSIX shell on Windows.
# On Windows the .bat wrapper is used, everywhere else the shell wrapper.
set -euo pipefail

MODULE=":baselineprofile"
GENERATOR_CLASS="com.nuvio.tv.baselineprofile.BaselineProfileGenerator"
BENCHMARK_CLASS="com.nuvio.tv.baselineprofile.NuvioFrameMetricsBenchmark"
STARTUP_CLASS="com.nuvio.tv.baselineprofile.NuvioStartupBenchmark"
# The androidx.baselineprofile plugin adds a "benchmark" flavor dimension to the module, so the
# connected test tasks are <flavor><BuildType>AndroidTest:
#   * benchmarkRelease    -> the app under test is the minified release build (macrobenchmark)
#   * nonMinifiedRelease -> the app under test is the non minified release build (profile collection)
BENCHMARK_TASK="${MODULE}:connectedBenchmarkReleaseAndroidTest"
PROFILE_TEST_TASK="${MODULE}:connectedNonMinifiedReleaseAndroidTest"
PROFILE_COLLECT_TASK="${MODULE}:collectNonMinifiedReleaseBaselineProfile"

# Where the module keeps the additional test output (the json files written by androidx.benchmark).
ADDITIONAL_OUTPUT_DIR="baselineprofile/build/outputs/connected_android_test_additional_output"
# The human readable summary of the last run.
METRICS_FILE="baselineprofile/README.md"

if [ "${OS:-}" = "Windows_NT" ]; then
  GRADLEW="./gradlew.bat"
else
  GRADLEW="./gradlew"
fi

usage() {
  cat <<USAGE
Usage: $0 <generate-profile|collect-profile|benchmark|metrics|extract-metrics|release-with-profile>

Commands:
  generate-profile      Generate the app baseline profile on the connected device
                        (task :app:generateBaselineProfile, which drives the ${MODULE} module).
                        This rewrites app/src/main/generated/baselineProfiles/*.txt, so restore
                        those files afterwards when the profile is not the change under test.
  collect-profile       Collect the baseline profile with the generator of the ${MODULE} module
                        (tasks ${PROFILE_TEST_TASK} and ${PROFILE_COLLECT_TASK}).
  benchmark             Run the frame timing macrobenchmarks on the connected device
                        (task ${BENCHMARK_TASK}).
  metrics               Run the macrobenchmarks and refresh ${METRICS_FILE} from the
                        json output of the run.
  extract-metrics       Only refresh ${METRICS_FILE} from the output of the last run.
  release-with-profile  Build the release APK/AAB with the merged baseline profile artifacts.

Runner arguments (passed through to the instrumentation):
  -Pandroid.testInstrumentationRunnerArguments.degradedAllowed=<journey.key>
                        Opt a journey out of the degraded-journey assertion, e.g. `player`.
                        A journey that could not reach its target (no title to open, no
                        playable surface, an unreachable drawer entry) fails the benchmark by
                        default, because its numbers do not describe the guard rail.
                        The trailer player of the detail screen is the usual case on an
                        emulator without stream sources: there the player journey always
                        degrades, so it is run with `degradedAllowed=player`.

Environment:
  ANDROID_SERIAL        Device to use (defaults to the only connected device).
  CI_USE_DEBUG_SIGNING  Set to true when no release keystore is available, the release build
                        of :app is then signed with the debug key.
USAGE
}

# Runs the macrobenchmark on the connected device.
# The startup guard rail lives in its own class, so it needs its own instrumentation run.
run_benchmark() {
  "$GRADLEW" "$BENCHMARK_TASK" \
    -Pandroid.testInstrumentationRunnerArguments.class="${BENCHMARK_CLASS}" \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
    "$@"
  "$GRADLEW" "$BENCHMARK_TASK" \
    -Pandroid.testInstrumentationRunnerArguments.class="${STARTUP_CLASS}" \
    -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=EMULATOR \
    "$@"
}

# Collects the baseline profile with the generator of the module.
run_profile_collection() {
  "$GRADLEW" "$PROFILE_TEST_TASK" \
    "$PROFILE_COLLECT_TASK" \
    -Pandroid.testInstrumentationRunnerArguments.class="${GENERATOR_CLASS}" \
    "$@"
}

# Turns the androidx.benchmark output into the summary table.
extract_metrics() {
  if command -v python3 >/dev/null 2>&1; then
    PYTHON=python3
  elif command -v python >/dev/null 2>&1; then
    PYTHON=python
  else
    echo "python is required to extract the metrics" >&2
    return 1
  fi

  "$PYTHON" - "$ADDITIONAL_OUTPUT_DIR" "$METRICS_FILE" <<'PY'
import glob, os, re, sys

src, dest = sys.argv[1], sys.argv[2]
files = sorted(glob.glob(os.path.join(src, "**", "additionaltestoutput.benchmark.message_*.txt"),
                         recursive=True))

percentiles = re.compile(r"P50\s+(-?[\d.]+),\s+P90\s+(-?[\d.]+),\s+P95\s+(-?[\d.]+),\s+P99\s+(-?[\d.]+)")
frame_count = re.compile(r"frameCount.*\[median\s+([\d.]+)\]")
# The startup metric reports min/median/max instead of percentiles.
startup_median = re.compile(r"timeToInitialDisplayMs.*?\[median\s+([\d.]+)\]")

def method_of(path):
    base = os.path.basename(path).split("additionaltestoutput.benchmark.message_")[-1]
    if base.endswith(".txt"):
        base = base[:-4]
    return base.rsplit(".", 1)[-1]

rows = []
startup_rows = []
for path in files:
    name = method_of(path)
    with open(path, "r", encoding="utf-8") as handle:
        content = handle.read()
    row = {"test": name, "frames": "", "cpuP50": "", "cpuP95": "",
           "overrunP50": "", "overrunP95": "", "startupP50": "", "startupP95": ""}
    count = frame_count.search(content)
    if count:
        row["frames"] = count.group(1)
    for metric, prefix in (("frameDurationCpuMs", "cpu"),
                           ("frameOverrunMs", "overrun")):
        match = percentiles.search(content.split(metric, 1)[1]) if metric in content else None
        if match:
            row[prefix + "P50"] = match.group(1)
            row[prefix + "P95"] = match.group(3)
    startup = startup_median.search(content)
    if startup:
        row["startupP50"] = startup.group(1)
    if row["startupP50"]:
        startup_rows.append(row)
    else:
        rows.append(row)

if not rows and not startup_rows:
    print("no benchmark output found under %s" % src)
    sys.exit(1)

with open(dest, "a", encoding="utf-8") as handle:
    if rows:
        handle.write("\n## Macrobenchmark frame metrics\n\n")
        handle.write("| test | frames (median) | frameDurationCpuMs P50 | frameDurationCpuMs P95 | frameOverrunMs P50 | frameOverrunMs P95 |\n")
        handle.write("| --- | --- | --- | --- | --- | --- |\n")
        for row in rows:
            handle.write("| %s | %s | %s | %s | %s | %s |\n" % (
                row["test"], row["frames"], row["cpuP50"], row["cpuP95"],
                row["overrunP50"], row["overrunP95"]))
    if startup_rows:
        handle.write("\n## Macrobenchmark startup metrics\n\n")
        handle.write("| test | timeToInitialDisplayMs (median) |\n")
        handle.write("| --- | --- |\n")
        for row in startup_rows:
            handle.write("| %s | %s |\n" % (row["test"], row["startupP50"]))
print("wrote %s" % dest)

# Silent profile fallback check: when the profile is applied but the numbers do not improve,
# the profile was most likely not installed for the package. This is a warning, not a failure.
by_test = {row["test"]: row for row in rows}
for name, row in sorted(by_test.items()):
    if not name.endswith("WithBaselineProfile"):
        continue
    counterpart = by_test.get(name[:-len("WithBaselineProfile")] + "WithoutCompilation")
    if counterpart is None:
        continue
    with_p50, without_p50 = row["cpuP50"], counterpart["cpuP50"]
    if not with_p50 or not without_p50:
        continue
    with_p50, without_p50 = float(with_p50), float(without_p50)
    if without_p50 <= 0:
        continue
    if with_p50 >= without_p50 * 0.9:
        print("WARNING: %s with profile P50 %.1f ms is within 10%% of %s without compilation "
              "P50 %.1f ms - the baseline profile may not have been applied (silent fallback)."
              % (name, with_p50, counterpart["test"], without_p50))
PY
}

cmd="${1:-}"
if [ -n "$cmd" ]; then
  shift
fi

case "$cmd" in
  generate-profile)
    # Canonical task of the baseline profile plugin, it drives the :baselineprofile module.
    "$GRADLEW" :app:generateBaselineProfile "$@"
    # The task rewrites the generated profile of the app, so the working tree is checked
    # afterwards: a dirty profile directory means the profile of this lane leaked into the
    # working tree and has to be reviewed before the change is committed.
    if [ -n "$(git status --porcelain -- app/src/main/generated/baselineProfiles 2>/dev/null)" ]; then
      echo "" >&2
      echo "WARNING: the generated baseline profile of :app is dirty:" >&2
      git status --porcelain -- app/src/main/generated/baselineProfiles >&2
      echo "WARNING: review (and usually restore) app/src/main/generated/baselineProfiles" >&2
      echo "WARNING: before committing, the generated profile must not leak into the change." >&2
      echo "" >&2
    fi
    ;;
  collect-profile)
    # Same generator, run directly from the module and collected by the module task.
    run_profile_collection "$@"
    ;;
  benchmark)
    run_benchmark "$@"
    ;;
  metrics)
    run_benchmark "$@"
    extract_metrics || exit 1
    ;;
  extract-metrics)
    # Only refresh the summary table from the output of the last run.
    extract_metrics
    ;;
  release-with-profile)
    "$GRADLEW" :app:assembleRelease :app:bundleRelease "$@"
    ;;
  *)
    usage
    exit 1
    ;;
esac
