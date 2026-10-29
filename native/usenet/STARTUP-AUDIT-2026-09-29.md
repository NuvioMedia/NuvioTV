# Usenet startup audit — 2026-09-29

This is a diagnostics-first pass. The cold parse/index/selection pipeline stays
synchronous so a device measurement can guide the next change. No early-selection
parser, asynchronous index ownership, or new article scheduling policy is added.

## Findings and changes

* **NNTP setup already overlaps the NZB download.** `Providers` sets `SkipPing`
  and `MinConnections`; `NewClient` starts connection workers and returns without
  waiting for their DNS/TCP/TLS/authentication. Another top-level parallel task
  would not hide those same waits a second time. The new pool-setup and first-NNTP
  metadata timings distinguish setup from the later wait for a usable response.
* **Cold cache writes block selection.** `fetchNZB` parses the complete document,
  compresses/checksums/writes every file's index record, then reopens the cache
  and replaces the in-memory tables with lazy records before `Select`. This
  deliberately releases unselected segment maps. Moving just the write into a
  goroutine would keep all maps alive longer and require safe ownership during
  selection, cancellation, hint writes and cache eviction. Measure before changing.
* **Provider close held a global lock while waiting for socket workers.** Creation
  and close now run outside that lock. Per-account barriers retain the rule that
  a closing account must finish before its replacement connects; unrelated accounts
  can proceed. Waiting for a barrier is cancellable by the open request, while
  an adopted shared pool retains server lifetime.
* **DELETE forced full GC and OS-memory release.** Removed synchronous
  `debug.FreeOSMemory()`. Session buffers and sockets still close; Go's configured
  soft memory limit and normal scavenging remain active. An idle engine is stopped
  after the existing playback idle timeout or on backgrounding.
* **Default startup constructed the sidecar even with launch prewarm disabled.**
  Lifecycle callbacks and non-Usenet cleanup/player hooks no longer construct it.
  Usenet source results now warm only the process/trust roots, without an NZB or
  provider connections. A two-minute idle deadline bounds this warm runtime.
  Explicit launch prewarm and full result prefetch retain their separate settings.
* **Addon endpoints could reach local services and leak custom headers on redirects.**
  Both HTTP and NNTP now check each actual resolved socket target before connecting.
  Default policy blocks localhost, private/shared ranges and special-use addresses.
  The new device-local “Allow self-hosted servers” setting permits private,
  loopback and shared-address services. Link-local/metadata, multicast, unspecified
  and transition/documentation destinations stay blocked. The HTTP transport does
  not use environment proxies, which could bypass target validation. TLS verification
  remains enabled. Public/self-hosted transports and provider leases are separated.
  HTTP(S) redirects are bounded; HTTPS downgrade and URL-userinfo redirects fail.
  All addon headers are removed across an origin change (including port changes
  and subdomains), even if a later hop returns to the original origin. Same-origin
  signed URLs/headers remain unchanged. Hop-by-hop and proxy credentials are ignored.

## Measurements

`BenchmarkNZBColdStartupStages`, three cold trials per case, Windows x64,
i7-1260P, Go 1.27.0. HTTP is loopback and serves gzip; these are synthetic NZBs
with 160,000 segments. Other build work was running on this host. These figures
are a diagnostic sanity check, not a physical-TV speedup claim.

| Shape | XML size | Parse work | Index including I/O | Filesystem portion | Reopen | Allocated per operation |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 100 files × 1,600 segments | 38.8 MB | 876 ms | 69 ms | 9 ms | 10 ms | 180 MB |
| 1 file × 160,000 segments | 39.3 MB | 1,043 ms | 82 ms | 22 ms | 10 ms | 183 MB |

Allocation totals are **not peak retained heap or RSS**. The parser already
decodes one file at a time, but every file's segment map remains in memory until
index binding. A single huge file also temporarily has the decoded XML segment
slice and its normalized segment/prefix tables. The file/segment caps are checked
after `DecodeElement` for a file; enforcing the segment cap during decoding is a
useful further robustness improvement, particularly for malformed input.

Raw output: [cold-start-stages-2026-09-29.txt](benchmarks/cold-start-stages-2026-09-29.txt).
The older [indexed-cache benchmark](benchmarks/nzb-cache-2026-09-15.txt) remains
useful context for warm hits, but is not a before/after test of this change.

## Diagnostics on the TV

Play using **ExoPlayer**, then open **Settings → Advanced → Usenet streaming**.
The existing saved diagnostics now include a separate NZB stages card:

* Sidecar startup/reuse, session request, player handoff, decoder and first frame.
* Provider-pool setup and first NNTP metadata wait (queue/connect/auth/article header;
  not a separate TLS-only measurement).
* NZB cache lookup and hit/miss, HTTP request-to-headers, time inside body reads,
  parse/decompression work outside those reads, index/write, its filesystem portion,
  and cache reopen/binding.
* Selected-file readiness, MKV head/index article readiness, first media bytes
  returned to an HTTP reader, range waits and total resolve-to-first-rendered-frame.

Marks are monotonic elapsed times. Durations overlap; do not add all rows together.
Parsing consumes the body as it arrives, so body read time includes waiting and
transport work; it is not the complete download wall time. Cache filesystem time
includes pruning/locking and buffered writes/close/rename, not a disk-flush guarantee.
The first media-byte mark describes bytes returned by the content reader, not a
decoded video frame. The total excludes addon search and browsing before resolve.
Prepared sessions keep their earlier engine clock; the app clock starts at selection.

Logs use `UsenetStartup`, `UsenetStartupStages` and `UsenetStartupRange`. Reports
contain fixed stage names, counts and timings, not URLs, credentials, filenames,
article IDs or payloads. The saved report is still the latest successful ExoPlayer
startup; a failed open or mpv/external-player run does not create a first-frame report.

For the device comparison, keep profile, connection limit and Fast MKV unchanged;
leave full result prefetch off. Test an uncached title, repeat the same source for
a cache hit, then try a large multi-file NZB. Record all four cards and the cache
outcome. Engine-already-running distinguishes source-flow warmup from a cold
process. Disable launch prewarm for the default behavior.

## Next decisions after device measurements

1. If parsing dominates, investigate bounded per-file token decoding and lower
   allocation conversion first. Early selection needs explicit semantics: default
   largest-video selection, ambiguous episode fallback, RAR set ordering and
   subtitle enumeration may depend on files later in the document. A strict
   explicit selector can eventually offer a narrower safe fast path.
2. If index/write dominates, consider deferred persistence with a single session-
   owned job and a bounded metadata handoff. Preserve lazy cache reads, atomic
   publication, cancellation and cache-generation recovery.
3. If first-NNTP metadata dominates, add DNS/TCP/TLS/auth attribution, then revisit
   connection warmup counts. Current automatic allowance can be large (up to
   4,096 total) and creates one worker per configured slot, even though most sockets
   are lazy. Avoid increasing speculative work without evidence.
4. Keep existing priority demand, progressive article sharing, bounded byte/segment
   read-ahead and Fast MKV leases. `ServeContent` still owns single/multipart/suffix
   Range semantics with one independent reader per request. No new payload disk
   cache or redundant HEAD/STAT round trips were introduced.

Idle snapshot close can still flush bounded hints and close sockets synchronously;
removing forced GC does not make all cleanup free. The new timing breakdown should
be used to decide whether that remaining cleanup needs separate instrumentation.

## Validation

* Final native engine suite: **79 top-level tests passed under the Go race
  detector in WSL**, with three opt-in experiments skipped. Includes their
  subtests for ranges, archives, cache recovery, startup leases, security and
  blocked pool creation/close. See [saved output](benchmarks/startup-review-race-2026-09-29.txt).
  Earlier Windows runs intermittently underfilled the scheduling-sensitive
  pipeline-depth fixture; the complete final Linux race run passed it at depths
  2, 4 and 8. The new timing test uses a blocking reader rather than relying on
  server sleep being observed before the client is scheduled.
* Five targeted Android JVM unit tests passed (settings, pending playback
  ownership and Usenet routing).
* Full debug APKs rebuilt for all four ABIs. SHA-256 comparison confirmed every
  native engine in the universal APK equals the rebuilt prebuilt binary.
* Android instrumentation APK compiled, but **zero instrumentation tests ran**:
  the application crashed during Hilt/OkHttp initialization because the test APK's
  `j$.util.DesugarTimeZone` lacks `getTimeZone(String)`. Therefore the new Compose
  diagnostics and source-warmup instrumentation tests are not runtime-verified.
  This is separate from the normal APK: installing it directly on the Android 16
  x86_64 emulator and cold-launching `MainActivity` succeeded.
* Physical-TV/provider latency remains unmeasured; the APK is for that comparison.
