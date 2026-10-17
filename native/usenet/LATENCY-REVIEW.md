# Usenet responsiveness review — 2026-09-08

Scope: the added Usenet engine, its Android lifecycle and its own settings.
Shared ExoPlayer and global player defaults were not changed in this work.

1. **Prewarm: implemented, default on.** Bootstrap Go and Android trust roots on
   an IO coroutine when the app becomes visible. Reuse the resolve mutex and
   profile checks, keep the idle runtime ready while browsing, and stop it when
   backgrounded. Disabling the setting stops an idle child immediately; active
   playback survives changes. Provider sockets still require a playback request.
   The tradeoff is idle runtime memory. Zero cold-start time is not guaranteed if
   Play races bootstrap, the profile changes, or Android terminates the process.
2. **Skip sniff for named video: rejected as written.** The first progressive
   read learns authoritative decoded yEnc file size. NZB segment byte counts are
   encoded wire lengths; skipping this step would break Content-Length, suffix
   ranges and seeks. sniff reads 16 progressive bytes, not an entire article,
   and the shared article is retained for subsequent readers. It never checked
   the signature in the named-video branch. Existing exact-range tests cover
   genuine wire lengths different from decoded lengths.
3. **Cancel abandoned demand: implemented with a discovery exception.** Last
   playback lease now cancels unfinished demand as well as speculative bodies.
   Live overlapping readers retain their leases; completed articles stay in the
   bounded LRU. Applying the suggested one-line edit globally caused repeated RAR
   header downloads in four existing tests. Explicit discovery readers preserve
   header handoffs, while playback can cancel an inherited header download.
4. **Dual cap: implemented.** Per-reader read-ahead is limited by the configured
   segment count and 12/24/32 MiB by profile. Known lengths are authoritative;
   unknown articles use NZB estimates. Actual allocations retain the existing
   hard store budget. This bounds speculative work; it does not guarantee zero
   connection resets or instantaneous provider reconnection.
5. **0.8-second ExoPlayer rebuffer threshold: deferred.** Outside the clarified
   engine-only scope. Loopback does not remove upstream provider latency, jitter,
   or decode/keyframe dependencies, so the claimed safe threshold is unsupported.

Additional change: an explicit FileReader seek immediately releases obsolete
leases even before the next read. RAR boundary discovery follows the same byte
distance cap as article read-ahead.

Validation includes shared-reader cancellation, header-to-playback ownership,
byte/count limits, 4 MiB article seeks and sequential byte verification, plus the
existing native range, archive, cache, retry and subtitle suite. Android coverage
adds prewarm enable/disable, duplicate startup prevention, background cleanup and
foreground re-entry to the packaged sidecar tests.

No millisecond improvement is claimed without physical-TV/provider measurements.
Compare the same release and settings against the 2026-09-07 APK: launch then
browse before Play, seek far forward/back several times, and play through a RAR
volume boundary. The prewarm switch is under Advanced > Usenet streaming.

The final native Go suite and full WSL race-instrumented engine suite pass.
The Balanced TCP smoke benchmark passed four runs (two per link): 11.09–11.20
MiB/s on the 100 Mbit/s simulation and 31.06–31.15 MiB/s on the 400 Mbit/s
simulation. Cold seek reads took 298–368 ms. See
`benchmarks/windows-tcp-latency-2026-09-08.csv`. The compiler and emulator were
also running, so this is a correctness/throughput smoke check, not an isolated
before/after performance comparison. It does not establish a throughput gain
over the baseline, whose faster-link results were higher.

The final APK built successfully for all four ABIs. All three packaged Android
sidecar tests passed on the Android 16 TV emulator (zero skipped/failed). The
universal APK's v2 signature and 16 KiB ZIP alignment verified, and all packaged
engine binaries match the final build hashes. Installable APKs and checksums are
in `artifacts/2026-09-08-usenet-latency` at the workspace root. The wider JVM suite
was not rerun for these engine-only changes; its prior baseline limitations remain
documented in VALIDATION.md.
