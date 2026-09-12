# Lint triage and repeatable Windows verification

The starting `fullDebug` report contains 3,199 errors, 1,817 warnings and 25
informational findings. This is an inventory of diagnostics, not a count of
distinct runtime defects. No baseline, global opt-in, `abortOnError=false` or
blanket suppression is introduced by this work.

The final source lint run on 12 September 2026 reports **3,029 errors, 1,817
warnings and 25 hints**: 170 fewer errors. Lint still fails, with exit code 1.
The complete inventory is in
`output/verification/20260912-123434-057/lint-inventory.json`.

| Category | Before | After | Difference |
| --- | ---: | ---: | ---: |
| MissingTranslation | 2,303 | 2,303 | 0 aggregated issues |
| UnsafeOptInUsageError | 729 | 562 | -167 |
| RestrictedApi | 59 | 59 | 0 |
| RememberInComposition | 33 | 30 | -3 |

The three translated Italian strings remain missing in other locales, so their
aggregated MissingTranslation issues remain. The 167 declaration opt-ins and
three remembered fallbacks account for the entire error reduction; the warning
and hint totals have not changed. The final lint run included the added source
and generated unit-test model and completed in 351.4 seconds without heap OOM.

| Starting category | Count | Decision |
| --- | ---: | --- |
| MissingTranslation | 2,303 | Real localization backlog; use a native reviewer per locale. Three missing Italian VPN failure messages were translated. An issue can list several missing locales, so three added strings need not remove three issues. |
| UnsafeOptInUsageError | 729 | Use AndroidX opt-in at declarations that deliberately depend on unstable Media3 APIs. Three settings declarations account for 42 findings. Three Media3-derived audio classes account for 105, and the RTL subtitle object for 20. These now opt in individually. Revalidate the rest with their owning player/extractor code. |
| RestrictedApi | 59 | 53 occurrences involve TV provider integration (26 ProgramBuilder, 27 AndroidTvChannelManager); six involve activity/navigation. Audit the resolved dependency annotations and supported replacements before changing behavior. |
| RememberInComposition | 33 | Thirty sites construct requesters through a retained map's `getOrPut`; three sites construct an uncached empty-list fallback in SupportersContributorsScreen. The three fallback constructors now use `remember`. |

## Focus identity is part of TV behavior

The reported map calls return the stored requester while a key remains present.
Unlike calling a constructor on every recomposition, this preserves identity and
also shares it with focus-restoration callbacks. Wrapping all calls in a new
`remember(key)` is not a harmless fix: if a map entry is removed and repopulated,
composition can retain the old requester while callbacks use the new one.
Keep these thirty diagnostics visible pending a focused focus-registry design
and navigation tests. No suppression hides them. The three empty-list fallback
requesters had no cache; they now retain identity across recompositions of that
branch, until it leaves composition.

## Restricted APIs

The TV provider diagnostics concern builder methods and constants inherited from
library implementation classes. The activity/navigation diagnostics concern
navigation back-stack state. Replacing constants with magic numbers or upgrading
dependencies solely to silence lint is not justified. Before changing these
paths, verify Watch Next insert/update/delete and launcher preview channels on
an actual TV, as well as back navigation. This task leaves those integrations
unchanged. The local custom media decoder dependencies are a separate reason to
review compatibility before general dependency upgrades.

## Resource review

The three added Italian strings are `vpn_error_tun_creation`,
`vpn_error_dns_resolution` and `vpn_error_handshake_timeout`. Existing playback,
loading and timeout resource names have Italian entries in the starting tree.
Presence is not a proof of translation quality. English text copied into every
locale would conceal the backlog and is not an acceptable completion strategy.

## Separate verification processes

Run from PowerShell 7 with a JDK 17 and Android SDK installed:

```powershell
./scripts/verify-windows.ps1 -JavaHome 'G:/tools/jdk/jdk-17.0.20.1+1' -AndroidSdk 'G:/tools/android-sdk'
```

The script executes JVM tests, APK assembly and lint in three sequential Gradle
invocations. It records each exit code and elapsed time under
`output/verification/<timestamp>/results.json` and fails overall if any phase
fails. It continues to collect the other phase results after a Gradle failure.
Lint still blocks success. Logs are local build diagnostics and should be
reviewed for secrets before sharing.

The 12 GiB heap and two-worker defaults reflect a previously successful local
build; they are maximum heap limits, not a guarantee of sufficient physical RAM.
Use `-HeapGiB` and `-Workers` to fit another machine. The script does not modify
`gradle.properties`; `--no-parallel`, single-use daemons and in-process Kotlin
compilation bound overlapping Gradle/compiler work. Do not run another build
at the same time. `-Phase Test`, `-Phase Build` or `-Phase Lint` select one phase;
`-DryRun` prints the planned invocations without starting Java.

`--configure-on-demand` also avoids configuring the unused local FFmpeg module
and attempting an unrelated NDK installation in this checkout. Local signing
was supplied through the existing `NUVIO_RELEASE_STORE_FILE`,
`NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD` and
`NUVIO_RELEASE_STORE_PASSWORD` environment variables using the local audit
debug keystore. The script does not supply or store signing credentials.

The script was additionally exercised with a fake Gradle wrapper in an isolated
build directory: Test returned 0, Build returned 7 and Lint returned 0. All
three results were recorded and overall verification failed as required.

## Final JVM results and skipped coverage

The final frozen-source test run succeeded in about 123 seconds with **1,284
total test cases: 1,281 passed, zero failures, zero errors and three skipped**.
The run includes all 12 privacy tests, nine controller startup integration
tests and three report error-detail matching tests. Evidence:
`output/verification/20260912-125052-323/results.json` and `test.log`; JUnit XML
is under `app/build/test-results/testFullDebugUnitTest/`.

Existing skipped cases are:

- `LocalhostZeroCopyDataSourceTest.testHttpError404`
- `DefaultAllocatorTest.testLateReleasedAllocationsMemoryLeak`
- `FrameRateUtilsAfrTest.live test real extensionless debrid MP4 URL detects frame rate`

Earlier development runs saw a compile snapshot mismatch and then two new
encoded-key privacy regressions tested against a prior compiled implementation.
The final run rebuilt the frozen source and passed those regressions. Only the
final results above represent completion; earlier failing logs remain available
for traceability. No verification phase encountered a heap out-of-memory error.

The final APK assembly also succeeded, in 121.2 seconds, using the same frozen
source. Evidence: `output/verification/20260912-125337-479/results.json` and
`build.log`. The x86_64 emulator APK is
`app/build/outputs/apk/full/debug/app-full-x86_64-debug.apk`, produced on
2026-09-12 at 12:55:34 +02:00, with 99,719,327 bytes and SHA-256
`745899D486FD7193FFE75A30AB438FA2FDB74523A3924B4EF143871E8EE95DE2`.
This is a debug APK signed with the local audit key, not a production release.

Obtain comparable, message-free totals after lint:

```powershell
python scripts/lint_inventory.py app/build/reports/lint-results-fullDebug.xml
```

Do not reuse the starting numbers as final measurements: archive the fresh report
and inventory alongside the run results. Unit tests and an emulator cannot
certify hardware decoding, HDR, audio passthrough or HDMI behavior on a real TV.
