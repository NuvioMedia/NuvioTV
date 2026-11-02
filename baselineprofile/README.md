# Baseline profiles & frame-metric guard-rails

This module holds the macrobenchmark code of the NuvioTV TV app:

| class | purpose |
| --- | --- |
| `BaselineProfileGenerator` | collects the baseline profile of the app |
| `NuvioFrameMetricsBenchmark` | frame timing guard rails (`frameDurationCpuMs`, `frameOverrunMs`) |
| `NuvioStartupBenchmark` | startup guard rail (`timeToInitialDisplayMs`) |

The shared building blocks live in `NuvioUi.kt` (labels, package resolution, locale pinning,
home screen preparation) and `NuvioJourneys.kt` (the five journeys).

## Running the gate

```sh
# from Git Bash (or any POSIX shell), with the emulator on adb
export CI_USE_DEBUG_SIGNING=true        # only when no release keystore is available

scripts/perf_baseline.sh generate-profile   # collect the baseline profile
scripts/perf_baseline.sh metrics            # run the benchmarks and write the summary
scripts/perf_baseline.sh extract-metrics    # only refresh the summary from the last run
```

`metrics` writes the summary of the run to `baselineprofile/build/outputs/perf-metrics.md`.
The file is a build artifact, so it is not tracked by git.

Other commands:

| command | effect |
| --- | --- |
| `collect-profile` | collects the baseline profile with the generator of this module (`connectedNonMinifiedReleaseAndroidTest` + `collectNonMinifiedReleaseBaselineProfile`) |
| `benchmark` | runs the frame timing macrobenchmarks only |
| `release-with-profile` | builds the release APK/AAB with the merged baseline profile artifacts |

### Environment prerequisites

| item | value |
| --- | --- |
| `CI_USE_DEBUG_SIGNING` | `true` when no release keystore is available. The release build of `:app` is then signed with the debug key. |
| `ANDROID_SERIAL` | the device to use. Without it the only connected device is used. |
| `DEGRADED_ALLOWED` | comma separated list of journey keys that may degrade without failing the gate. Default: `player`. |

### Device expectations

- A TV device or emulator. The journeys drive the D-pad only, they never use touch.
- The device has to be unlocked before the instrumentation starts, otherwise the app never
  gets the focus and every journey degrades.
- The app under test is the `benchmark` build type of `:app`. Its application id carries the
  `.debug` suffix (`com.nuvio.tv.debug`), while the release build keeps `com.nuvio.tv`. The
  package under test is resolved from the runner argument the baseline profile plugin fills
  in, and the device package list is only the fallback.
- The labels of the journeys are the English ones. The generator and the benchmarks pin the
  device locale to `en-US` for the duration of the run, so a device in another language does
  not degrade every label lookup.

### Degraded journeys

A journey that could not reach its target (no title to open, no playable surface, an
unreachable drawer entry) fails the benchmark, because the numbers of such a journey do not
describe the guard rail. The `degradedAllowed` runner argument opts a journey out of that
assertion:

```sh
-Pandroid.testInstrumentationRunnerArguments.degradedAllowed=player
```

The `player` journey is the documented exception on an emulator without stream sources: there
is no playable surface, so the journey degrades by design. The script passes
`degradedAllowed=player` by default, overridable through the `DEGRADED_ALLOWED` environment
variable.

The gate fails only when a test fails **outside** the allowed degraded set. The summary of the
run lists the outcome of every test.

## Reading the numbers

- **The table is bimodal.** Every journey appears twice: once with the baseline profile applied
  (`*WithBaselineProfile`) and once without any compilation (`*WithoutCompilation`). The unit of
  comparison is the *vertical pair* of one journey, never a single row: the question the gate
  answers is "how much does the profile buy on this journey", not "is this number good".
- **4K rasterisation floor.** A 4K AVD renders a 3840x2160 panel, so the host rasterisation of
  that panel sets a floor under every frame duration. The absolute values are therefore *not*
  representative of a real TV, where the panel is much cheaper to drive.
- **Only same-device numbers are comparable.** A different API level, a different system image, a
  different resolution or a different ABI changes the floor and invalidates the comparison. The
  before/after sides of a change have to come from the same device.
- **The gate is human-in-the-loop.** There is no automated threshold in the repository. The
  macrobenchmark produces the numbers, a human compares the two columns and decides. The tooling
  only guards the *plumbing*: the extraction fails when the run produced no benchmark output at
  all, and it prints a warning when the with-profile column lands within 10% of the
  without-compilation column, which is the signature of a silent profile fallback
  (`BaselineProfileMode.UseIfAvailable` measures the uncompiled app when no profile is
  installed for the package).
- **A degraded journey is not a number.** A journey that could not reach its target fails the
  benchmark instead of reporting a number for a screen it never reached, see the degraded
  journeys above.

## The baseline profile

The profile is collected on the device and written into the app:

| file | content |
| --- | --- |
| `app/src/main/generated/baselineProfiles/baseline-prof.txt` | the merged profile of all journeys |
| `app/src/main/generated/baselineProfiles/startup-prof.txt` | the launch path of the app |

The generator collects the startup portion of the app (the launch and the settle of the first
screen) with the startup flag, and the five journeys without it. The startup profile therefore
carries the launch path only, the merged profile carries the rules of all five journeys.

Regenerating the profile rewrites the two generated files, so they have to be reviewed (and
usually restored) when the profile is not the change under test.

## Follow-ups

Deferred, not implemented yet:

- **Journey timing rework.** The sleeps and waits inside `NuvioJourneys.kt` are the timings of
  the current baseline. Any rework of them invalidates the comparability of the numbers, so it
  is scheduled after the profile has been re-baselined.
- **Marker cleanup and journey reordering.** The markers of the journeys and the order of the
  journeys of the generator change the collected profile, so they have to land together with a
  profile regeneration to keep the profile and the guard rails comparable.
