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

## Follow-up: resource contracts and full lint triage (verification pending)

This section records the subsequent request to fix the remaining 3,029 lint
errors. It does not replace the earlier verified run above. Fresh Gradle test,
lint and APK results for this larger change must be recorded separately before
claiming completion. No language has been removed or disabled, no missing
translation has been filled by copying English, and no lint baseline or global
suppression has been introduced.

### Translation inventory and Italian completion

The prior 2,303 aggregated MissingTranslation findings represented **21,251
missing language/resource pairs** across 32 reported locale codes. Lint groups
several missing languages into a single finding, so counting findings as strings
to translate substantially understates the work.

All 37 reported Italian gaps were translated: custom-theme controls (21), Hi10P
software-decoding settings (2), the floating navigation indicator (2), removal
of a recent search (1), and provider login controls/messages (11). Existing
placeholder positions and types were preserved. A static inventory of all
default and Italian values XML now finds **3,035 translatable string, plural or
string-array names in each, with zero missing Italian names**. This establishes
resource coverage, not a native-speaker review of every historical translation.

The remaining previously reported missing pairs total **21,214**. These are not
a fresh lint measurement; scope choices about supported product languages are
still pending and must not be inferred from the Italian completion.

| Locale code | Remaining reported missing pairs |
| --- | ---: |
| ar | 223 |
| bg | 550 |
| bs | 1687 |
| cs | 415 |
| da | 538 |
| de | 968 |
| el | 108 |
| es | 101 |
| fr | 442 |
| hi | 2143 |
| hu | 222 |
| in | 797 |
| it | 0 |
| iw | 286 |
| ja | 534 |
| lt | 2139 |
| nl | 84 |
| no | 2018 |
| pl | 102 |
| pt | 163 |
| ro | 2298 |
| ru | 101 |
| sk | 110 |
| sl | 989 |
| sq | 218 |
| sr | 139 |
| sv | 1633 |
| ta | 974 |
| tr | 197 |
| uk | 509 |
| vi | 84 |
| zh | 442 |

The inventory groups locale variants as reported by lint; it is not a per-folder
count. Raw inventory is local `tmp/lint-missing-translation-inventory.json`.

### Formatting and plural contracts

- Six Bosnian metadata/stream error strings now use argument 1 for addon names,
  argument 2 for the content ID and argument 3 for its type. The two `issues`
  variants keep argument 4 for the issue summary; these defects were hidden from
  a simple maximum-argument-count check.
- Lithuanian QR expiry now consumes the already formatted duration string with
  `%1$s`, matching its actual caller, instead of requiring an integer.
- Three Russian and three Ukrainian TorrServer error resources now retain the
  path, exit-code or timeout argument supplied by existing callers. This is a
  resource-contract fix, not new torrent functionality. The separate Android 7
  process-termination compatibility fix guards an API introduced in Android 8.
- Donation-progress and automatic-cache labels are literal text and their callers
  do not pass formatting arguments. Their `formatted="false"` attribute states
  this actual contract; it is not a lint suppression. Existing accidental `%%`
  in Italian, Polish and Slovak cache labels was reduced to the displayed `%`.
- Arabic plural resources now include zero/two/few/many branches, Hebrew includes
  dual branches, and applicable Italian/French/Portuguese/Spanish resources include
  many branches. Existing local plural wording is retained where the forms agree;
  Arabic/Hebrew special forms use their local number grammar. The preexisting
  English loading-time plural in the Hebrew resource was translated into Hebrew.

`scripts/tests/test_resource_contracts.py` passed all three tests locally. They
parse the resource XML and verify targeted formatting contracts across every
translation, literal-percent semantics, and required quantity branches. This
does not replace Android resource compilation or the fresh lint run.

### Independent review notes

Scoped AndroidX Media3 opt-ins mark declarations that intentionally use unstable
APIs; they do not change decoders, upgrade Media3 or suppress unrelated findings.
The focus helper keeps the retained map authoritative and remembers creation
only. Real Compose recomposition tests cover reordering, disposal/reentry,
replacement, clearing and pruning; actual focus navigation still needs a device.

Configuration servers now load a dedicated raw PNG whose bytes match the original
base logo; the existing HTTP image/png contract is preserved. Overlay Back handling
retains remote-key precedence through the root preview handler, while its
predictive-Back callback exists only while the overlay is visible. A continuously
registered disabled callback was rejected during review because later destination
callbacks could otherwise gain precedence.

The provider migration uses public ContentValues columns in place of restricted
builder APIs. Review checks preserve intended numeric/string field types and the
explicit null clearing for missing preview artwork, logos and unknown playback
duration/position. Provider capture tests and fresh execution results are pending;
this source review does not certify launcher behavior on every physical TV.

### Fresh follow-up lint result

The new fullDebug lint run completed with **2,303 errors, 1,763 warnings and
25 informational findings**. Every remaining error is MissingTranslation;
non-translation errors are now **zero**, a reduction of **726** from the 3,029
errors at the start of this follow-up. UnsafeOptInUsageError, RestrictedApi,
RememberInComposition and the resource-format/type/plural error categories no
longer contain errors. Lint still returns failure because the 2,303 translation
findings remain visible.

Evidence: `output/verification/20260912-133032-117/lint-inventory.json` and its
associated lint log. Italian name coverage is 3,035/3,035 with zero gaps; other
language pairs from the inventory above remain at 21,214. The user has not yet
selected a narrower supported-language scope, so no locale removal or filtering
has been applied.

The first follow-up JVM run passed 1,290 tests with 3 excluded (1,293 total),
including four provider ContentValues capture tests and five real Compose
recomposition tests. Its evidence is
`output/verification/20260912-132831-533/results.json`. Final tests/build after
the last small Back-key adjustment and the actual emulator instrumentation runs
are still pending; this intermediate test result is not presented as their result.

The final-source JVM rerun subsequently passed **1,290 tests with 3 excluded
(1,293 total), zero failures/errors**, in approximately 1 minute 53 seconds.
Evidence: `output/verification/20260912-134454-921/results.json` and `test.log`.
The final APK build also passed in 364.1 seconds, with
evidence in `output/verification/20260912-133351-731/results.json` and `build.log`.
The combined Python discovery under `scripts/tests`, with PYTHONPATH pointing to
the repository's `scripts` directory, passed **30/30 tests** in 4.645 seconds:
19 existing release tests, eight HTTP fixture tests and three resource-contract
tests. Summary: `output/verification/lint-language-python-results.json`.

Two actual Android provider instrumentation tests have passed, and the emulator
focus/sidebar path to Settings was checked. The final overlay instrumentation
rerun and the last confirming lint run are still pending at this documentation
update; no physical TV result is claimed.

The confirming lint run after the final Back-key change reproduced **2,303
MissingTranslation errors, zero other errors, 1,763 warnings and 25 hints**.
Evidence: `output/verification/20260912-134656-870/lint-inventory.json` (about
2 minutes 58 seconds). Only the final overlay instrumentation result remains
pending among the planned checks at this update.

### Lifecycle regression found and corrected by device test

The stable Android instrumentation test subsequently reproduced a real ordering
bug: a destination opened while the root overlay was already visible could
consume dispatcher Back before the overlay. The keyed BackHandler was replaced
with an Activity-lifecycle callback, restored to last registration when the
navigation entry resumes; disposal removes both observer and callback. Remote
Back remains intercepted by the root preview handler.

The new APK build passed in 293.2 seconds:
`output/verification/20260912-135752-268/results.json`. APK SHA-256:
`783857EE594BA2732B428E606D6380F80F19F3E5B31B77A7476A88375CD275BA`.
All **three Android instrumentation tests passed in 3.569 seconds**, including
two real provider CRUD tests and the overlay test covering remote Back,
dispatcher Back, subsequent delegation, and real Settings navigation with an
already visible overlay. Evidence:
`output/lint-device/instrumentation-final.txt`.

The JVM and lint results above predate this production lifecycle fix; new final
runs are required and underway. This supersedes the previous statement that only
the overlay check remained. No physical TV test is claimed.

Final verification after that lifecycle correction: JVM **1,290 passed, 3 skipped, 1,293 total, zero failures/errors**, 115.8 seconds, evidence `output/verification/20260912-140322-260/results.json`. Confirming lint completed in 177.6 seconds with **2,303 MissingTranslation errors, zero other errors, 1,763 warnings and 25 hints**, evidence `output/verification/20260912-140606-734/lint-inventory.json`. Build and all three Android tests above refer to this corrected APK. Lint still fails; locale scope remains undecided and all existing languages are retained.
