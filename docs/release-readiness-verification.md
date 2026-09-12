# Release verification and native compatibility

## Distribution contract

The updater, `release_beta.py`, and `release-metadata.sh` share fixture-based
channel tests. A missing suffix or exactly lowercase `brusus.<digits>` denotes
a stable fork release. Any other nonempty suffix is a prerelease, including
alpha/beta/rc on major zero. Build metadata does not change the channel.
Malformed dotted identifiers are rejected; the shell reads an unprefixed
Android versionName while tag helpers accept the `v` prefix.

The release workflow runs the complete full-debug and full-release JVM suites,
then assembles the optimized release in a separate Gradle process. Each process
uses one worker and in-process Kotlin compilation. Heap is computed from actual
available RAM, reserving 4 GiB and capped at 10 GiB; machines below 8 GiB
available fail clearly. [GitHub's public Ubuntu runner documentation](https://docs.github.com/en/actions/reference/runners/github-hosted-runners)
currently specifies 16 GB RAM. This avoids copying the Windows workstation's
12 GiB setting into an unknown runner environment.

The standalone `release_beta.py` CLI remains Linux/Bash-oriented. Its old
unflavored APK path was corrected to `app/build/outputs/apk/full/release`, with
the exact five `app-full-<abi>-release.apk` names. It runs the same separate
verification tasks and performs signing/metadata/native gates before any
publication step. No release/tag operation was invoked in local verification.

On Windows, `scripts/verify-windows.ps1` exposes `ReleaseTest`, `ReleaseBuild`
and `AndroidTest` (debug instrumentation) phases. `All` retains the existing
debug test/build/lint sequence. Final optimized verification uses
`-HeapGiB 10 -Workers 1`.

## Signing identity and package validation

The trusted certificate is the public signer of the already published
[0.9.0-brusus.13 arm64 APK](https://github.com/brusus/NuvioTV/releases/download/0.9.0-brusus.13/app-full-arm64-v8a-release.apk),
verified locally with Android apksigner. SHA-256:
`1a93cecc3673d870cd05d673af3807f75478b2b517921d588c60a27d2c002199`.
Only this public fingerprint is committed; no private signing material is read
or committed during local verification. CI exports its configured public
certificate and checks it before compilation. A failure to unlock the keystore
or a mismatched identity stops the job. Certificate rotation requires an
intentional signing migration and review of this pin.

Before upload/publication, every expected ABI APK and the universal APK must
exist, verify cryptographically, use the pinned signer, identify as
`com.nuvio.tv.brusus`, carry the exact requested versionName/versionCode, and
remain non-debuggable. ZIP alignment is checked too. Local optimized builds use
the existing disposable audit key and therefore intentionally cannot pass the
production identity gate; their R8/runtime validation is separate evidence.

## Native audit and dependency changes

`scripts/audit_native_libraries.py` reads ELF program headers and ZIP entries
without loading or executing native code. It reports every LOAD alignment,
ABI, archive and content hash. Its strict 16 KiB gate applies to arm64-v8a and
x86_64; 32-bit entries remain visible in the report. ELF alignment and ZIP
alignment are distinct requirements described in the [Android page-size guide](https://developer.android.com/guide/practices/page-sizes).
An aligned binary is not proof that every runtime path works on a 16 KiB device.

The previous APK contains 4 KiB-aligned Conscrypt, IAMF and WireGuard binaries.
The following narrowly scoped upgrades were inspected from official Maven AARs:

- Conscrypt 2.5.2 to 2.6.3: all four native ABIs have 16 KiB LOAD alignment;
  minSdk is 21. [Official 2.6.3 release notes](https://github.com/google/conscrypt/releases/tag/2.6.3)
  describe the corrective BoringSSL rebuild. The broader 2.7 TLS changes were
  not needed for this compatibility fix.
- WireGuard tunnel 1.0.20230706 to 1.0.20260102: arm64 and x86_64 have 16 KiB
  LOAD alignment, and minSdk remains compatible at 24. The 32-bit binaries
  remain 4 KiB aligned. Published versions are available in the [official Maven metadata](https://repo.maven.apache.org/maven2/com/wireguard/android/tunnel/maven-metadata.xml).

Cached dependency AAR inspection found that only `libc++_shared.so` actually
collides between mpv and libass. Broad `pickFirst` exceptions for FFmpeg and
TorrServer were removed so future conflicting binaries fail packaging. The mpv
and libass C++ runtimes differ: native clients require two floating-point
`from_chars` symbols provided by mpv's runtime and absent from libass's copy.
No required symbol unique to libass's runtime was found in these clients.
The existing packaged x86_64 APK selects mpv correctly. Release verification
pins the audited mpv runtime hashes for all four ABIs to catch dependency-order
changes, rather than assuming `pickFirst` will always select a compatible file.
Both the original AAR bytes and the NDK 29 `llvm-strip --strip-unneeded` result
are accepted. Each stripped input was independently generated from the mpv AAR
and matched the packaged library byte for byte in all four ABIs. This preserves
the runtime identity check while allowing the normal release stripping step.
The incompatible libass runtime is explicitly rejected by a regression test.

Intermediate native evidence is local under `build/release-audit/`; downloaded
AARs, prior APKs, test keys and build outputs are excluded from commits.

## Optimized version 14 build evidence

The final version is `0.9.0-brusus.14`, versionCode `1073`. Standalone fullRelease
R8 assembly passed with a 10 GiB heap and one worker in 7m57s. The local SDK's
incomplete NDK entry was then preserved as a backup and linked to the verified
complete NDK 29 installation. Native stripping was rerun successfully (19s),
then final release packaging passed (26s), without changing application source.
Evidence: `output/verification/20260912-173841-108/results.json`.

Release instrumentation compiled and minified against the release mapping in
53s (`output/verification/20260912-174000-574/results.json`). All five target APK
SHA-256 values were unchanged before and after instrumentation assembly.
However, that runner crashed before executing tests: R8 inlined the target's
`androidx.tracing.Trace` away while AGP omitted that shared dependency from the
test APK. No production keep rules were added to accommodate the test runner.
The unsupported release-instrumentation opt-in was removed. Native integration
tests run against debug; optimized-release UI/playback smoke is separate proof.

Pre-localization x86_64 target SHA-256 (superseded below):
`DA945D5FA07CE2A67533F4B19A2D8595DE6BFF923DD48390265A2ED55343FAC5`.
Failed experimental mapped release test APK SHA-256:
`74C8807F96C924B42346C5D91DD3705454D99489F5B87A808D34DE79C6B2B69F`.
Both use the disposable audit certificate, not the production signer.

All five release APKs pass exact package/version/ABI, non-debuggable and ZIP
alignment checks. `build/release-audit/release14-native-audit.json` confirms zero
64-bit ELF alignment failures and zero incompatible runtime selections across
all splits and universal. `build/release-audit/stripped-runtime-proof.json`
records the independent raw-to-stripped runtime comparison.

Both final JVM variants passed: fullDebug in 315.0 seconds and fullRelease in
120.4 seconds, each with 1,321 cases (1,318 passed, three existing skips, no
failures/errors). Evidence: `output/verification/20260912-174224-533` and
`output/verification/20260912-174834-736`. These JVM suites exercise classes
before shrinking; the separately installed release APK provides the R8 runtime
smoke evidence. The final Python release/resource/fixture suite passed 52 tests.

The final debug native integration suite passed all three tests on the API 36
TV emulator: Conscrypt performed a verified local TLS exchange, WireGuard JNI
initialized without starting a VPN, and IAMF decoding matched the exact
upstream PCM golden output. Evidence:
`output/prerelease14-device/native-debug-instrumentation.txt`.
The emulator uses 4 KiB pages; 16 KiB compatibility is established here by ELF
and APK ZIP inspection, not by execution on a 16 KiB device.

The standalone optimized release cold-launched in 422 ms and played the local
H.264/AAC fixture, advancing to 8,762 ms, pausing at 11,047 ms, then resuming at
11,052 ms. Debug native integration and release playback are distinct checks.
The existing two TV-provider CRUD tests also passed. The overlay/navigation
regression passed in isolation after opening Home; a combined invocation first
failed a sidebar setup precondition, which is retained in the device evidence.

The final Python invocation mirrors CI:
`PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests -v`.
All 52 tests passed in 31.052 seconds on the Windows workstation using Git Bash
for shell fixtures. This validates the Linux-only runner's explicit Windows
rejection and its guidance to the supported PowerShell verification path.

## Final localized release snapshot

Fresh lint initially identified two newly missing updater error messages, beyond
the existing 2,303 translation findings. Both new messages were translated in
all 34 remaining locale files (68 additions). All resource XML parsed and the
four resource contract tests passed. The changes contain two added lines per
locale, without bulk formatting changes.

The resulting optimized release was rebuilt successfully in 3m09s with the same
10 GiB/one-worker budget: `output/verification/20260912-180241-713`.
Kotlin/Java and native tasks remained up-to-date; R8 and resource packaging ran.
The first localized x86_64 APK SHA-256 was
`AE13957F9178E2589B4CD333E7D26D560B5F122E26D142C29CFB1D224FDB581F`.
All five final APK identities, exact version 14/code 1073, ABI declarations,
non-debuggable flags, audit signatures and ZIP alignment passed again; full
hashes are recorded in `build/release-audit/final-release-apk-checks.json`.

Every native library is byte-identical to the previously tested release
snapshot. `build/release-audit/final-release-native-audit.json` inventories 240
entries across all five APKs, with zero 64-bit ELF alignment failures and zero
runtime-selection failures. Native integration evidence therefore remains
applicable to the unchanged binaries; it was not rerun merely for translated
strings. The complete JVM suites likewise preceded this resource-only change.

The final localized release APK also passed a fresh device smoke: cold launch
1,049 ms, local fixture playback, pause at 13,843 ms and resume at 13,849 ms,
with no reported player error. This replaces the earlier pre-localization smoke
as the final artifact check. Evidence is under `output/prerelease14-device`.

Hungarian uses `values-hu/string.xml` (singular), so a strings.xml-only inventory
initially missed it. A subsequent full lint caught that omission. Both Hungarian
messages were then added, and a focused regression now groups every XML file in
each locale before asserting coverage of the two new keys. All 36 base/locale
directories contain both keys. Final Python suite: 52 passed; resource contracts:
4 passed. This is a scoped completion check for the new messages, not suppression
of the existing translation backlog.

The HU-complete final release build passed in 3m13s:
`output/verification/20260912-181057-842`.
Its x86_64 APK SHA-256 supersedes the earlier localized hash:
`7D5CAA73EC27C240467E648E9DD6A084183BBFC10A82D04BA432CD54E6BA1290`.
All five identity/signature/ABI/ZIP gates passed again, and the native audit still
reports 240 entries, zero 64-bit alignment failures and zero runtime failures.
Every native library remains byte-identical to the tested native snapshot.
The final-release JSON evidence files above now refer to this HU-complete build.

The HU-complete final APK passed its own final device smoke: cold launch 953 ms,
fixture playback, pause at 14,964 ms, resume at 14,971 ms, no player error and an
empty crash buffer. Screenshot:
`output/prerelease14-device/release14-hu-final-playing.png`.

Final fullDebug lint completed in 3m06s and reports exactly 2,303 errors, all
`MissingTranslation`, plus 1,754 warnings and 25 hints. There are zero technical
errors and zero missing-translation findings for the two newly added messages.
The earlier localization backlog remains unresolved; full lint still exits
nonzero. No baseline or suppression was added. Evidence:
`output/verification/20260912-181446-513/results.json` and
`build/release-audit/final-lint-inventory.json`.
