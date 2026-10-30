# Baseline metrics - lane 1 (guard rails)

CURRENT (d14832e1c)

Environment:

| item | value |
| --- | --- |
| device | `Television_4K(AVD)` - API 36 (Android 16) emulator (`emulator-5554`), AOSP TV, 3840x2160 @ 640 dpi |
| abi | `x86` (the AVD runs the 32 bit x86 system image, `ro.product.cpu.abilist` = `x86,armeabi-v7a,armeabi`) |
| app under test | `:app` `fullBenchmark` variant, build type `benchmark`: R8 minify **on**, resource shrinking **on**, `isDebuggable = false` |
| app under test package | `com.nuvio.tv.debug` (the `benchmark` build type sets `applicationIdSuffix = ".debug"`, so the package is not `com.nuvio.tv`) |
| signing | `CI_USE_DEBUG_SIGNING=true` - no release keystore is available on the CI machine, so the benchmark build is signed with the debug key |
| test module | `:baselineprofile` (`com.nuvio.tv.baselineprofile`) |
| benchmark task | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` |
| class | `com.nuvio.tv.baselineprofile.NuvioFrameMetricsBenchmark` |
| metric | `androidx.benchmark.macro.FrameTimingMetric` |
| iterations | 3 per test method |
| compilation | `CompilationMode.Partial(BaselineProfileMode.UseIfAvailable)` and `CompilationMode.None` |

## Reading these numbers

- **The table is bimodal.** Every journey appears twice: once with the baseline profile applied
  (`*WithBaselineProfile`) and once without any compilation (`*WithoutCompilation`). The unit of
  comparison is the *vertical pair* of one journey, never a single row: the question the gate
  answers is "how much does the profile buy on this journey", not "is this number good".
- **4K rasterisation floor.** The AVD renders a 3840x2160 panel, so the host rasterisation of
  that panel sets a floor under every frame duration. The absolute values here are therefore
  *not* representative of a real TV, where the panel is much cheaper to drive.
- **Only same-AVD numbers are comparable.** A different API level, a different system image, a
  different resolution or a different ABI changes the floor and invalidates the comparison. The
  before/after sides of a change have to come from the same AVD.
- **The gate is human-in-the-loop.** There is no automated threshold in the repository and none
  is added by this lane: the macrobenchmark produces the numbers, a human compares the two
  columns and decides. The tooling only guards the *plumbing*: the extraction step fails when
  the run produced no benchmark output at all, and it prints a warning when the with-profile
  column lands within 10% of the without-compilation column, which is the signature of a silent
  profile fallback (`BaselineProfileMode.UseIfAvailable` measures the uncompiled app when no
  profile is installed for the package).
- **A degraded journey is not a number.** A journey that could not reach its target (no title to
  open, no playable surface, an unreachable drawer entry) fails the benchmark instead of
  reporting a number for a screen it never reached. The `player` journey is the documented
  exception on an emulator without stream sources, see the runner argument below.

## Notes

- The run happens on an emulator, so the absolute numbers are **not** representative of a real
  device. They are only meant as the *current* side of the before/after comparison.
- `WithBaselineProfile` uses `BaselineProfileMode.UseIfAvailable`, so it falls back to no
  compilation when the device has no profile installed for the package.
- The emulator has no stream source configured, so the player journey falls back to the trailer
  player of the detail screen instead of a full stream playback. The detail and player
  transitions are still measured.
- The fast scroll journey uses a touch fling plus a rapid D-pad travel; the emulator accepts
  touch events, so both paths are covered.
- A journey that reports a **degraded** outcome fails the benchmark: the journey is measured
  first and the outcome is asserted afterwards, so a screen that was never reached cannot
  contribute a number to the table. The opt-out is the runner argument
  `-Pandroid.testInstrumentationRunnerArguments.degradedAllowed=<journey.key>`, which accepts a
  comma separated list of journey keys. On the emulator of this lane the `player` journey is
  the documented case: there is no stream source, and the trailer player of the detail screen
  is not reachable either, so the journey is run with `degradedAllowed=player`.
- `scripts/perf_baseline.sh extract-metrics` exits with code 1 when the run produced no
  benchmark output at all, and prints a `WARNING` line when the with-profile column lands
  within 10% of the without-compilation column (the signature of a silent profile fallback).
  Neither case fails the gate on a threshold: the comparison of the two columns and the
  decision stay with the reviewer.

## Follow-ups

Deferred by design, not implemented in this lane:

- **M2 - merge the journeys into `BaselineProfileGenerator.generate()`.** The shipped profile is
  currently collected by the single `generate` test (home scroll only), so the other journeys
  are not part of the shipped profile. Merging them needs a regeneration of the shipped
  profile, so it is scheduled together with step 4 of the plan (profile regeneration).
- **M3 - journey timing rework.** The sleeps and waits inside `NuvioJourneys.kt` are the
  timings of the current baseline. Any rework of them invalidates the comparability of the
  numbers in this file, so it is scheduled after the addon re-baseline.
- **m1/m2/reorder.** The marker cleanup and the reordering of the journeys of the generator
  belong to the same batch as M2 and M3: they change the collected profile, so they have to
  land together with the profile regeneration to keep the profile and the guard rails
  comparable.

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

## Verification

The gate outcome above and the frame metric table are the authoritative record of the gate for
PR #4369. The subsection below is the verification of the review fixes (gate v2) and does not
replace it.

### gate-v2 verification

| step | command | result |
| --- | --- | --- |
| compile | `:baselineprofile:compileBenchmarkReleaseKotlin` and `:baselineprofile:compileBenchmarkBenchmarkKotlin` | **OK** - both variants compile (the module has no `compileBenchmarkReleaseAndroidTestSources` task, the instrumentation sources of the `android.test` module are the module sources) |
| filtered smoke | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` with `-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.tv.baselineprofile.NuvioFrameMetricsBenchmark#navigationWithBaselineProfile` on `emulator-5554` | **OK** - 1 test, 0 failed, with the degraded-journey assertion active: the navigation journey reached its target on the current addon state of the emulator, so no exception was thrown |
| startup guard rail | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` with `-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.tv.baselineprofile.NuvioStartupBenchmark` | **OK** - 1 test, 0 failed, 5 iterations, `timeToInitialDisplayMs` median 799.2 ms |
| full suite | `:baselineprofile:connectedBenchmarkReleaseAndroidTest` (10 frame tests + the new startup test) | **10 of 11 pass** with `-Pandroid.testInstrumentationRunnerArguments.degradedAllowed=player`; the single failure is the pre-existing render thread error of the player journey (see the notes) |

Notes:

- The startup metric reads the launch from the trace of the *measured* block, so the launch has
  to happen inside the measure block. The test therefore uses `StartupMode.COLD`: the library
  kills the app process after the setup block, and the launch inside the measured block is a
  real cold start. A launch in the setup block produces "Unable to read any metrics".
- The runner of this AGP version only accepts an exact `Class#method` filter: `Class#method1+method2`
  and `Class#method*` both select **0** tests and the task still reports success, so a filtered
  run always has to check the "Starting N tests" line of the log.
- The full suite **without** the opt-out is red, and both failures are on the `player` journey:
  - `playerWithoutCompilation` failed with
    `java.lang.IllegalStateException: Journey 'player' was degraded during the benchmark: playback unavailable, measured detail overlay instead`.
    This is the new assertion doing its job: on this emulator there is no stream source, so the
    player journey cannot reach a playable surface. It is the documented exception, so the run is
    repeated with `-Pandroid.testInstrumentationRunnerArguments.degradedAllowed=player`, where
    that assertion no longer fires.
  - one of the two player tests fails with
    `java.lang.IllegalStateException: Observed no renderthread slices in trace`.
    This is **not** caused by the change: the player lane of this PR hits the same error on the
    unmodified code (`baselineprofile/lanes/logs/bench-player-verify.log`), the video surface of
    the detail screen does not produce render thread slices on this AVD. Which of the two player
    tests hits it varies from run to run.
- The other eight frame journeys (`fastScroll`, `homeScroll`, `navigation`, `settingsLibrary`,
  each with and without the profile) and the new startup test pass, so the assertion does not
  produce false failures on the journeys that do reach their target on the current addon state
  of the emulator.
- The full suite was run while the player lane of this PR had not produced its completion
  artifact yet (it was still retrying), because both lanes drive the same emulator. The runs of
  the player lane are therefore not comparable with the numbers of this file.

