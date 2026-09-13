# Native Usenet playback

This checkout adds Stremio `nzbUrl`, `servers`, `fileIdx` and `fileMustInclude`
streams to NuvioTV's normal stream selection, autoplay, source switching and
episode switching paths. Both ExoPlayer and mpv receive an ordinary loopback
HTTP URL. Provider credentials remain in memory.

## Architecture

`UsenetSidecar` launches `nativeLibraryDir/libnuvio_usenet.so` with
`ProcessBuilder`. The file is a Go PIE executable, not a JNI library. Android's
native library installer supplies its executable location. The APK contains
arm64-v8a, armeabi-v7a, x86 and x86_64 variants, linked for API 24 and 16 KiB page
alignment. RapidYenc's SIMD kernels are built from source with the NDK.

The parent passes a random control token, public Android trust roots and the
runtime memory target through stdin. The child binds only `127.0.0.1` on a random
port. Control requests require the token; media/subtitle URLs carry independent
unguessable session capabilities. No credentials appear in arguments or files.
Parent pipe EOF terminates the child even if Android kills the parent abruptly.

Deleting a session cancels its requests, closes the NNTP pool and releases its
buffers. Pre-warm Usenet Engine (on by default) starts the local daemon on app
foreground and keeps it ready while browsing, without opening provider sockets.
With **Prefetch First Usenet Result** enabled (default off), the first available
Usenet result on the **stream results page** is immediately prepared using the
normal session open. Its bounded MKV warmer obeys **Fast MKV Startup**. Detail pages do
not trigger stream searches or indexer requests. The results collector supplies
the candidate; no separate addon search is made. A newly higher-ranked Usenet
result replaces the previous candidate. Clicking the same source joins its
opening request or adopts its ready session, including parsed NZB data and cached
articles. Identity includes selection, episode, headers, providers, settings and
profile. A different selection opens normally; preparation failures stay silent.
Only one unused session is kept, for at most two minutes after opening, and it is
released when leaving the results page or backgrounding. Preparation is skipped
while the sidecar has active playback. Completed article bytes remain in the
existing memory LRU after warmup leases expire; there is no extra video cache.
Changing settings invalidates unused preparation without stopping adopted playback.

**Cache NZB Files** defaults on and is independent of result prefetching. It stores
validated NZB documents in the app's private cache directory, surviving session
deletion and process restarts. Each valid hit renews a 14-day idle lifetime.
The total, including an in-flight write, is capped at 256 MiB, with at most 256
documents and 64 MiB per document. Space is reserved in 1 MiB growth steps;
least recently used documents are evicted as needed. Plain XML is supported at
the full size allowance; compression is optional. The parser separately limits
both downloaded and decompressed NZB bytes to 64 MiB, with an explicit size-limit
error, and retains its 10,000-file and 500,000-segment bounds.
Entries are keyed by a digest of the
request URL, headers and profile scope; each hit creates fresh session metadata.
Expired/corrupt entries refetch normally, incomplete writes are discarded, and
disk errors do not fail playback. Turning the switch off bypasses and clears this
cache. Article/video payloads are never persisted by this feature.
Startup diagnostics distinguish NZB disk hits/misses, miss reasons, write
outcomes and saved document size from the session's in-memory article cache hits.
With prewarming disabled, an idle daemon survives for up to 30 seconds between
playbacks. An idle daemon exits when the app backgrounds. Active playback retains its session. A profile change
restarts the child to apply its memory target. Idle session deletion also returns
unused Go heap pages to the OS; steady playback does not force garbage collection.

The engine adapts AltMount's progressive shared-article model, NNTP pool, NZB
planning, archive strategy and volume naming. See [NOTICE.md](NOTICE.md) for
versions, licenses and the local pool corrections.

## Streaming and memory

* All article, video, RAR and native subtitle payloads are memory-backed. There is
  no video disk cache or extraction directory. Only NZB documents may be cached.
  Usenet also bypasses Nuvio's optional
  VOD disk cache, file-backed AFR probe and extra Java Range prefetch layer.
* Reusable power-of-two article slabs have a hard profile budget. Referenced
  slabs cannot be evicted. Idle cache entries are evicted for new prefetch;
  speculative work leaves one quarter of the budget for playback demand.
  Releasing a reader retains completed articles in an evictable LRU: later
  Range readers and archive/header reads can reuse them. Recycling immediately
  would retain the same slab in the free pool while losing the cache hit.
  ExoPlayer's sample back-buffer cannot answer the engine's raw byte requests,
  and mpv has a separate buffering policy. Session deletion drops both pools.
* Readers share progressive articles and have independent positions/windows.
  Closing or seeking a reader cancels its obsolete prefetch, while overlapping
  live readers keep their leases. Unowned playback demand cancels too. Short
  discovery readers allow header articles to finish for reuse across RAR signature
  and file-header parsing; a later playback owner can still cancel that body.
  Partial transfers replay into the same slab,
  verify the published prefix and append only new bytes. Late callbacks from
  abandoned attempts are sealed off before reuse.
* NNTP `BODY` commands are pipelined on persistent connections. Demand uses the
  priority lane and prefers idle connections. Large abandoned bodies close their
  connection rather than draining irrelevant megabytes. Pool replay ordering
  was corrected for replies already buffered behind an earlier response.
* yEnc decoding is chunked, in-place SIMD with Go CRC32 validation. Only control
  headers are parsed as lines. Payloads do not use a scanner or line decoder.
* NZB byte counts initialize a weighted segment plan. Authoritative zero-based
  offsets and lengths come from yEnc during ordinary body streaming and remain
  cached after payload eviction. An estimated offset is never served as data.
  There are no separate NNTP HEAD/STAT sizing or layout requests.
* HTTP supports HEAD, suffix/open-ended/single/multipart Range and 416 responses.
  Each request gets its own reader; session cancellation reaches active readers.

## Archives and selection

RAR resolution is always lazy. Stored RAR4 and RAR5 entries map directly to
underlying NNTP-backed extents. Compressed and encrypted entries are rejected.
The engine skips unselected packed data arithmetically and fetches only article
bodies containing required headers. It returns the selected entry before scanning
its continuation volumes. Near a volume boundary it resolves and primes just the
next extent using the current reader's read-ahead window; seeks cancel that work.
Mapped reads continue while another reader discovers later headers.

Numeric part names, `.rar/.r00/...`, `.001/...`, width-mismatched names, season
selection, yEnc name recovery and anonymous RAR5 volume numbers are supported.
Anonymous RAR5 headers are inspected only until the next required volume is found,
then their mappings are retained. RAR4 has no general internal volume ordinal;
fully anonymous RAR4 sets require meaningful NZB subject/release ordering or XML
ordering. Arbitrarily shuffled, completely anonymous RAR4 volumes cannot be
reconstructed authoritatively from their headers alone.

Cold deep RAR seeks may need successive continuation headers before the target
extent is known. Later seeks reuse their mappings. This avoids guessing archive
layout or eagerly scanning a season pack at startup.

Standalone NZB `.srt`, `.ass`, `.ssa`, `.vtt` and `.sub` entries are exposed as
stream-provided subtitles. Their article data is fetched only when selected.
Rendering depends on the selected player's format support; binary VobSub
`.idx`/`.sub` pairing is not reconstructed. Subtitles inside RARs are not eagerly
enumerated. The current NZB transport is
HTTP(S), including gzip; `fileMustInclude` accepts ECMAScript expressions and
Stremio `/expression/i` syntax, including lookarounds and backreferences.
Selector execution has time and backtracking-memory limits; expensive expressions
fail with an actionable error. Matching runs only during selection, never in the
streaming data path.

## Performance settings

Fast MKV Startup and the on-screen startup diagnostics are described in
[STARTUP-REVIEW.md](STARTUP-REVIEW.md). The optional head/tail warmup preserves
startup articles across the extractor's Cues seek; it defaults to ON. Both normal
opening and results-page preparation respect the toggle. Existing saved choices
are preserved. The latest timings appear directly in Usenet Settings.

Advanced Settings contains Performance Profile, Read-Ahead Segments and Max
Connections. Zero overrides mean automatic. Provider URLs can specify their own
connection allowance; Automatic respects that allowance. An explicit global cap
is divided across providers while preserving failover. Remaining connection
slots open lazily; the useful initial read-ahead window is pre-warmed in parallel
with the NZB download.

| Profile | Article budget | Read-ahead | Pipeline | Fallback connections |
| --- | ---: | ---: | ---: | ---: |
| Low memory | 32 MiB | 8 | 2 | 8 |
| Balanced (default) | 64 MiB | 16 | 4 | 16 |
| Throughput | 128 MiB | 32 | 4 | 32 |

These presets were selected from the included TCP experiment, not a fixed low
connection cap. Two simultaneous windows at a RAR boundary fit within the
speculative budgets for typical 768 KiB articles. Oversized articles and large
manual windows yield speculation under pressure. Runtime soft memory targets
are 96/144/224 MiB; these include Go overhead but are not an OS-enforced RSS cap.
The article budget is hard. Metadata, NNTP read buffers, TLS and the player's
own buffers additionally consume memory.

Read-ahead now also has a per-reader byte limit of 12/24/32 MiB for Low memory,
Balanced and Throughput, including the RAR boundary lookahead distance. It uses
known decoded lengths or conservative NZB wire estimates, so the segment count
is a maximum rather than a guaranteed window. The store's hard slab limit remains
authoritative when estimates are inaccurate. These latency changes were added
after the baseline performance matrix below; its figures are not measurements of
the updated build.

The [2026-09-09 tuning review](TUNING-REVIEW.md) investigates seek reconnects
and the apparent throughput regression. Both current limits are retained:
the Balanced smoke workload does not reach its byte cap, and a 1 MiB drain
removed reconnects but substantially increased cold-seek time on the simulated
shared links. The review includes a reproducible drain comparison and raw data.

The experiment transfers 48 MiB using 768 KiB decoded segments, two scheduler
CPUs, real TCP, independent yEnc encoding, a cold first read, a cold tail seek,
then sustained streaming. Nine configurations were run twice on each simulated
link. All 36 runs passed after the pool and eviction fixes. Raw results:
[windows-tcp-2026-09-07.csv](benchmarks/windows-tcp-2026-09-07.csv).

| Profile | 100 Mbit/s, 40 ms RTT | 400 Mbit/s, 80 ms RTT | First bytes, slower / faster link |
| --- | ---: | ---: | ---: |
| Low memory | 11.38 MiB/s | 27.15 MiB/s | 61 / 94 ms |
| Balanced | 11.34 MiB/s | 35.96 MiB/s | 70 / 95 ms |
| Throughput | 11.32 MiB/s | 40.74 MiB/s | 72 / 104 ms |

Eight connections saturated the slower link with a smaller window. Sixteen
connections and 16 segments improved the faster link considerably; 32/32 gave
the highest measured throughput. Pipeline depth 8 did not improve on depth 4
enough to justify the extra queueing. Slab allocation peaks were 24/48/64 MiB
for these cases, and measured allocations were approximately 80–100 per MiB
(including the in-process test server). Cold tail reads necessarily download
the containing NNTP article from its beginning; CSV includes those timings.

These are controlled host measurements, not measurements of a physical ARM TV,
Wi-Fi jitter or a paid provider. The Android TV emulator tests validate packaging,
ExoPlayer HTTP reads and lifecycle. Physical-TV/provider testing remains necessary
to tune for a particular device and account.

## Build and verify

Standard Android builds package prebuilt native engine binaries automatically from `prebuilt/`, requiring only standard Android SDK and JDK tools (no Go or C++ toolchains needed).

To recompile the native engine binaries from source, install Go 1.27+, NDK `29.0.14206865` and CMake `3.22.1`, then pass `-PbuildUsenetFromSource=true`:

```powershell
.\gradlew.bat :app:assembleFullDebug -PbuildUsenetFromSource=true
```

The debug package uses `com.nuviodebug.com` and retains the project's existing
release signing configuration. Configure `NUVIO_RELEASE_STORE_FILE`,
`NUVIO_RELEASE_STORE_PASSWORD`, `NUVIO_RELEASE_KEY_ALIAS` and
`NUVIO_RELEASE_KEY_PASSWORD` through environment variables or `local.properties`
before assembling or installing debug APKs. Usenet does not change signing policy.
APK outputs are in `app/build/outputs/apk/full/debug/`; the universal APK includes
all four ABIs, and ABI-specific APKs are smaller.

From `native/usenet`, with a working CGO C/C++ compiler:

```sh
go test ./...
go test github.com/javi11/nntppool/v4 -run TestBufferedReplyCommitsBeforeWriting
go test -race ./...
NUVIO_BENCHMARK=1 go test ./internal/engine -run TestPerformanceMatrix -v -timeout 15m
```

`NUVIO_BENCH_CASE=16/8/32` narrows the stress experiment. On this Windows host,
Go's Windows ThreadSanitizer could not reserve its address space; race tests were
cross-compiled and run in the existing Ubuntu WSL environment instead.

Android integration tests:

```powershell
rtk .\gradlew.bat :app:connectedFullDebugAndroidTest '-Pandroid.testInstrumentationRunnerArguments.class=com.nuvio.tv.core.usenet.UsenetSidecarTest'
```

The test uses an in-memory NZB/NNTP fixture, verifies exact ExoPlayer range bytes,
suffix ranges, control authentication, session cleanup, daemon reuse and parent
pipe EOF. No real provider credentials are required. The test APK built without
private service keys does not exercise Nuvio's optional account integrations.
