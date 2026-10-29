# Baseline metrics - lane 1 (guard rails)

CURRENT (d14832e1c)

Environment:

| item | value |
| --- | --- |
| device | Television_4K(AVD) - API 36 emulator (`emulator-5554`) |
| app under test | `:app` `fullRelease` variant (`com.nuvio.tv`), minified, not debuggable |
| test module | `:baselineprofile` (`com.nuvio.tv.baselineprofile`) |
| benchmark task | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` |
| class | `com.nuvio.tv.baselineprofile.NuvioFrameMetricsBenchmark` |
| metric | `androidx.benchmark.macro.FrameTimingMetric` |
| iterations | 3 per test method |
| compilation | `CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)` and `CompilationMode.None` |

## Notes

- The run happens on an emulator, so the absolute numbers are **not** representative of a real
  device. They are only meant as the *current* side of the before/after comparison.
- `WithBaselineProfile` uses `BaselineProfileMode.UseIfAvailable`, so it falls back to no
  compilation when the device has no profile installed for the package.
- The emulator has no stream source configured, so the player journey opens the trailer player
  of the detail screen instead of a full stream playback. The detail and player transitions are
  still measured.
- The fast scroll journey uses a touch fling plus a rapid D-pad travel; the emulator accepts
  touch events, so both paths are covered.

## Gate outcomes

| gate | command | result |
| --- | --- | --- |
| generate-profile | `:app:generateBaselineProfile` (generator `BaselineProfileGenerator#generate`) | **OK** - 1 test, 0 failed, `:baselineprofile:collectNonMinifiedReleaseBaselineProfile` and `:app:generateBaselineProfile` succeeded (10m55s) |
| benchmark | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` (`NuvioFrameMetricsBenchmark`) | **OK** - 10 tests, 0 failed (17m12s) |
| fallback | in-app JankStats export | not needed, the macrobenchmark produced the metrics |

The profile run rewrote `app/src/main/generated/baselineProfiles/baseline-prof.txt` and
`startup-prof.txt`; both were restored with `git checkout --`, so this lane ships no profile
change. `app/src/main/baseline-prof.txt` and `app/src/main/startup-prof.txt` were not touched.

## Reproducing

```sh
# from Git Bash (or any POSIX shell), with the emulator on adb
export CI_USE_DEBUG_SIGNING=true        # only when no release keystore is available
scripts/perf_baseline.sh generate-profile
scripts/perf_baseline.sh metrics        # runs the macrobenchmarks and refreshes this file
scripts/perf_baseline.sh extract-metrics # only refresh the table from the last run
```

Raw output of the run:

- `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/*/additionaltestoutput.benchmark.message_*.txt`
- `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/*/com.nuvio.tv.baselineprofile-benchmarkData.json`
- `baselineprofile/build/outputs/connected_android_test_additional_output/benchmarkRelease/connected/*/*.perfetto-trace`
- `baselineprofile/lanes/logs/` (gradle logs of every command of this lane)

## Macrobenchmark frame metrics

| test | frames (median) | frameDurationCpuMs P50 | frameDurationCpuMs P95 | frameOverrunMs P50 | frameOverrunMs P95 |
| --- | --- | --- | --- | --- | --- |
| fastScrollWithBaselineProfile | 114.0 | 6.6 | 21.8 | -8.0 | 6.5 |
| fastScrollWithoutCompilation | 114.0 | 20.6 | 22.8 | 5.5 | 7.8 |
| homeScrollWithBaselineProfile | 518.0 | 8.1 | 22.2 | -6.6 | 7.1 |
| homeScrollWithoutCompilation | 519.0 | 19.9 | 22.2 | 5.1 | 6.9 |
| navigationWithBaselineProfile | 279.0 | 6.0 | 21.7 | -8.6 | 6.8 |
| navigationWithoutCompilation | 284.0 | 19.0 | 30.6 | 4.6 | 22.2 |
| playerWithBaselineProfile | 421.0 | 5.3 | 21.4 | -9.3 | 6.2 |
| playerWithoutCompilation | 431.0 | 5.4 | 30.4 | -9.3 | 21.6 |
| settingsLibraryWithBaselineProfile | 620.0 | 5.5 | 21.0 | -9.1 | 6.0 |
| settingsLibraryWithoutCompilation | 617.0 | 5.8 | 21.4 | -8.8 | 6.4 |
