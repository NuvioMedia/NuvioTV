# Production-hardening validation: Xiaomi ARMv7

This validates ExoPlayer integration/lifecycle hardening, not another MC optimization.
Base Nuvio dev: `6377bee8cc644ecd8048a771ced61f06bcdf7946`.
Integration baseline A: `246fe581b980faa9fe60767fd49900dc8146a83b`.
Hardened B: `37c61de79b01fbb8aae2881ce2124cf644155d74`.
Both contain the same retained FFmpeg 7.0.2 coherent High10 MC implementation.
Original 151,680 differential cases / 1,320 real-frame hashes remain historical
correctness evidence; they were not repeated for this hardening comparison.

## Conditions and measurement

Xiaomi MiTV-AFMU0, Android 14/API34, armeabi-v7a, four Cortex-A55 cores.
Four FFmpeg frame workers, four inputs, sixteen outputs, synchronous conversion,
Native Memory OFF. Existing local Episode07 source reused: 1920x1080 AVC High10,
24000/1001fps, FLAC Japanese audio, Arabic ASS. Same URL, subtitles, audio and
display state. Android reported logical active mode39 / SF mode38,
1920x1080/120.00001Hz; this is not independent physical-panel frequency proof.

Three interleaved A/B pairs. Fixed-size temporary counters used immutable monotonic
42–55s boundaries, exactly13s. Seek/reset/generation change, interrupted traversal
or >20ms position-clock discontinuity invalidated measurement. Eleven deterministic
counter tests passed. No per-frame acceptance logging. Actual native post success,
decode completions, Media3 input/output drops and skips counted separately.
Window-local drop streak and completion lateness were recorded. Counters removed
from production source after acceptance; MC and native library were not changed.

Overhead controls each produced312 submissions/0drops: uninstrumented CPU247.99%,
instrumented247.37%; native video binaries identical. Start temperatures38.2/39.6C
were imperfectly matched; same sampled frequency/status and another uninstrumented
39C control supported no material measured playback perturbation, not zero cost.

## Exact burst results

“Posts” means successful Surface submission, **not confirmed physical presentation**.
Baseline legacy native success also represented an abandoned-Surface drop; these
steady windows had no Surface replacement/abandonment, and counters agree with the
external Media3 observer. CPU is process CPU / wall time;400% is possible on four cores.

| Run | Decode completions | Decode FPS | Posts | Post FPS | Drop % | Max consecutive output drops | Late completions | Min completion lead ms | Pre-burst / min frontier lead ms | CPU % | Start HAL C | Sampled burst HAL C |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|---:|---:|---|
|1A|318|24.462|312|24.000|0|0|0|380.910|376 / 339.910|253.23|40.1|45.9–46.7|
|1B|312|24.000|312|24.000|0|0|0|203.612|626 / 162.612|246.34|40.3|46.4–47.1|
|2A|311|23.923|312|24.000|0|0|0|348.344|668 / 307.344|246.33|40.2|45.8–46.6|
|2B|311|23.923|312|24.000|0|0|0|86.121|668 / 45.121|247.34|40.4|46.9–47.9|
|3A|315|24.231|312|24.000|0|0|0|267.290|501 / 226.290|251.94|40.4|46.8–47.3|
|3B|311|23.923|312|24.000|0|0|0|287.433|668 / 246.433|249.53|40.4|47.0–47.7|

Every valid window: zero dropped inputs/outputs and skipped inputs/outputs.
All six thermal recordings cover full windows; sampled HAL/global statuses0 and
all four reported frequencies2508000KHz. Starting temperatures matched within0.2C;
burst temperatures were not identical. Sampling cannot establish continuous peaks.
Completion FPS includes boundary/backlog effects, not a saturated decoder-capacity
measurement. Lead varies substantially; pair2B retained only45ms minimum frontier
lead, but never crossed into lateness. No measured rendered/drop regression;
do not infer a new performance improvement from CPU differences.

## Memory and Release/lifecycle checks

Post-window PSS for2A/2B:451577/431295KiB; native allocated heap151152/164146KiB.
For3A/3B:411542/406940KiB; native allocated heap148893/150751KiB.
Captured after window, before observer shutdown, not during measured burst.
Pair1 post-memory unavailable: instrumentation finish killed process, not zero usage.
Per-run snapshots cannot establish leak freedom.

Fresh isolated minified Release played Episode07 continuously to8:19. User directly
confirmed audible A/V sync and intact subtitles. Thirteen stability samples spanning
376.578s: PSS451240→434393KiB, peak490736; native PSS155220→131368KiB,
peak159388; current HAL47.0→49.7C, sampled peak49.9C/status0. No observed crash
or monotonic native growth in this interval; not full-episode/leak-proof evidence.
Episode01/06 short playback and transition succeeded. Pause/Home/foreground
recovered pixel-identical image with ASS. Natural20s synthetic completion confirmed
Android MediaSession STOPPED/errornull/finalposition20004ms.

Nine actual JNI/Surface tests passed under test targetSdk36:600 decoded hashes/PTS
including final delayed frame, five precise seeks/flush, full-pool release,
stale generation rejection, Surface abandonment, recreation/readback and teardown
races. Five player media-key seek inputs were also exercised without crash, but
individual player seek targets were not independently verified. PixelCopy readback
is not physical display proof. Synthetic fixture, not episode media, is the
reproducible JNI test input described in module README.

Minified Release:12 registered JNI signatures retained; defined dynamic export only
JNI_OnLoad; ELF LOAD alignment0x4000 and zipalign-P16 passed. VideoSO SHA256
`9fdc6abff9e9612679b20b2d8358e174e2f8c7f331606b0fe243006afa633170`.
Unchanged audioSO SHA256
`2789c8628e16b8e1b7b32fb934e93f93107c2f71f60d87206467b0e5314e1915`.
Fresh isolated Release APK SHA256
`29935f83aa4ec3ee25559cd1a4cdbc4164983f31375052c3a78f7d40c48608ad`.
Official installed app/account/data were not replaced.

## Scope and remaining gates

Fresh post-removal gate:56 module tests /3 application routing tests and full Debug
build passed(9m21s). Fresh actual JNI/Surface rerun passed9tests(84.726s), using direct
ADB instrumentation because Windows Gradle UTP failed before running any test with
an invalid device-provider output path. No test was weakened/skipped for this.
Fresh clean Debug APK SHA256
`813be1c83d601d64317fa1ae0a7953263c1a399bbc8e8f96675765e8ed51d127`
exactly matches the uninstrumented overhead-control APK. Actual Debug and saved
minified Release APK12JNI signatures reverified. Release build/device evidence above
was collected with clean production module before temporary probe insertion.
Unfiltered app suite:1874 tests,4 known stock failures,1 skipped, no additional
failure. Stock failures concern LocalhostZeroCopy error mapping, allocator retention
expectation, autoplay grouping expectation and blank Trakt credentials fixture.
Independent ContinueWatching cache/R8 cross-build serialization crash remains
outside High10 scope by explicit user choice; no cache/history deletion or fix.
Fresh Release identity does not validate this unrelated compatibility issue.

API24–27 color fallback, other devices/ABIs, DRM/rotation support, full52-minute
episode completion and precise numerical A/V offset are not claimed. Software
scope remains clear ARMv7 AVC High10 with rotation0 and ordinary Surface output.
Fresh independent reviewer could not initialize due token-optimizer MCP handshake
failure; author review is weaker. This report does not declare whole application
production-ready or authorize distribution/merge. No PR or push.

## Additional player-level seek gate

Follow-up on the same Xiaomi exposed a real early-seek failure: a seek before the
first output clears Media3 1.8's `formatQueue` while its first-input format marker
remains false. `DecoderVideoRenderer.processOutputBuffer` then receives a null
`outputFormat` and fails at line879. The bundled ExoPlayer AAR bytecode was inspected,
not assumed identical to upstream. A real Media3 feed/format-queue regression failed
with the expected format missing before the fix.

The isolated High10 renderer now re-registers the unchanged input format through
the protected API after a position reset. Decoder reuse is allowed only during
that synthetic notification, for equal formats; ordinary format-change reuse
policy remains unchanged. No FFmpeg, MC, Surface, threading or audio change.

Actual Episode07 programmatic seeks after the fix:

|Target(ms)|Observed READY position(ms)|Decoder generation|
|---|---|---|
|80000|80307|1|
|35000|35090|2|
|100000|100334|3|
|45000|45206|4|
|65000|65134|5|

Five additional rapid alternating seek calls reached generation10 and recovered
READY playback at66127ms, with no player error and no stale queued output at the
checked boundaries. Recovery took8557ms. An earlier observer's fixed5s wait expired
while buffering; this is not presented as a deadlock or a performance improvement.
The corrected observer uses a bounded30s READY/error condition. Queue snapshots
are not proof of physical display presentation; separate JNI generation/Surface
tests supply the ownership evidence.

All57 module unit tests passed after the change and full Debug built successfully.
Debug APK SHA256 `9850f1aeca07ad1663bed35e1718c22a510c9ad8e659899d97b7cb1dfafc6c14`;
its native videoSO remains byte-identical to the prior clean Debug binary
(`7a2e57d4f781595f0946d47158e2393f157c7ffd8064ee8adc6832b44cf78d56`).
The earlier six A/B windows and minified Release playback above predate this
seek-only correction; they are not relabeled as tests of this final revision.
Fresh minified FullRelease build passed(11m9s), with3 application policy tests and
57 module tests; final module rerun also passed57/57. Debug and Release actual DEX
checks each passed all12 JNI registrations; fresh Release zipalign-P16 passed.
New isolated Release APK SHA256
`bb7d20c49ee256d701b781b5119e9c926e582fa1ae74d5e2f75c00846e646cd2`.
Both Release native video/audio libraries match the earlier hashes above exactly.
The isolated Release was installed and cold-started successfully; no AndroidRuntime
error in its observed startup process. Repeated exact player seeks above were tested
in Debug, not relabeled as minified Release seek tests. Validation signing/identity
are local only, not production-distribution signing. Temporary addon removed again;
official installation/account/history unchanged.
Independent review remains unavailable:
another reviewer failed to initialize at the same required MCP handshake, before
any code review ran. Whole-application exclusions and coverage limits still apply.

## Independent commit order

Temporary acceptance/signing code is absent. No new performance optimization was
introduced after retained MC integration. Initial hardening verification covers the
first six code/test commits; the seek correction has separate follow-up evidence.
Every historical revision was not rerun.

| Commit | Subject / problem | Main files/area | Verification evidence |
|---|---|---|---|
|246fe581b|`feat(exoplayer): integrate isolated AVC High10 software renderer` — isolated software path without replacing audio|New module, native build/hook, renderer/detector, app factory/dependency|JVM tests, builds, actual APK JNI/export checks; historical MC correctness retained|
|2067eb406|`fix(hi10): validate decoder support per track` — reject incompatible MediaCodec / avoid audio-only unsupported playback|Profile detector, hardware renderer filter, app High10 policy/tracks/errors|Routing/profile/capability JVM tests and app policy tests|
|7cb3710fe|`fix(hi10): bound decoder lifecycle and drain delayed frames` — ownership/generations/EAGAIN/EOS|Lifecycle/backend, decoder, JNI, native lifecycle tests|18 lifecycle regressions; actual JNI600frames/finalPTS/hash, seek/flush/full-pool release|
|18ef1d13e|`fix(hi10): resolve Surface color metadata from libnativewindow` — owning-library symbol resolution|JNI data-space lookup, fixture/Surface tests and test Activity|Real black/white range and repeated paused-style Surface recreation/readback|
|2c3b2ffe9|`fix(hi10): distinguish Surface drops from successful submissions` — avoid false native-post telemetry|JNI/decoder/renderer/performance/probe, submission tests|Submission-status JVM regression and actual abandoned-Surface test|
|37c61de79|`test(hi10): match player target SDK in device regressions` — no legacy test target mismatch|Module testOptions only|56 JVM and9actualJNI/Surface tests under test targetSdk36|
|122f79357|`docs(hi10): record production hardening validation` — reproducibility and bounded acceptance evidence|README and this report|Documentation-only; preserved matching unit/build/device evidence|
|2242e86b4|`fix(hi10): restore format after early seeks` — prevent null output format before first frame|High10 renderer and real-Media3 format-queue regression|RED→GREEN;57 JVM/3 app policy tests, Debug/Release builds, actual Episode07 five precise plus five rapid seeks; native binaries unchanged|

Initial hardening comparison preserved24postfps/0windowdrops across three pairs. CPU/lead
variance is documented, not attributed to an individual commit. Documentation-only
acceptance updates are separate; generated media/binaries/logs are not committed.
