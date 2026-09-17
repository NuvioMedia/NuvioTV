# Fast MKV Startup

The toggle is under Settings → Advanced → Usenet streaming. It defaults to ON
and applies to the next session. Three cards immediately below the settings show
the last ExoPlayer startup, engine work, and cache activity. They reuse the DV
diagnostics card and row components and remain readable with the TV remote.
The latest report is stored in private app preferences; no clipboard action is
needed. Before a completed ExoPlayer startup, the card explains how to collect
one. Unobserved measurements are shown as an em dash.

## What changes

Opening a selected MKV starts two independent, session-owned article warmers.
The head warmer reads the first two articles. The index warmer waits up to 80 ms
for a Cues pointer from the bounded SeekHead scan, then fetches that article and
its successor (an index can cross their boundary). Without a pointer it falls
back to the tail, still accepting a late pointer within its existing pin budget.
When NZB caching has retained a Cues location for this exact content, the index
warmer starts there immediately and the head warmer skips the SeekHead scan.
The existing article budgets and connection limits still apply. Sparse decoded
segment offsets are also restored, reducing layout-correction fetches on repeat
opens. Fresh yEnc headers take precedence over persisted offsets.
Cues are not guaranteed to be in the last two articles of every MKV.
Stored RAR content uses the existing lazy volume mapping, so discovering the
archive layout can still delay reaching the tail.

Warmup leases survive ExoPlayer closing the first HTTP range to seek for Cues.
The head can therefore continue downloading while the extractor reads the tail,
and playback can join a download already in progress. A completed article is a
cache hit; prefetch does not guarantee completion before the player's seek.

Warmup is asynchronous and never delays returning the session URL. It is skipped
for non-MKV content and accounts with fewer than three configured connections.
Each worker retains at most four articles and at most one eighth of the article
budget, capped at 4 MiB. The store's existing hard memory limit remains in force.
Leases expire after eight seconds or immediately on session close. Warmup failure
does not fail playback. Ordinary demand retains its own article references.
A real content read beyond 32 MiB cancels only the head worker, without waiting
for cleanup on the HTTP path. Reads within 4 MiB of the advertised Cues address
or EOF retain the startup leases for the extractor's index round trip. This is a
bounded heuristic; the existing active reader handles resume-position read-ahead.
HEAD requests and ServeContent's size-discovery seeks do not trigger cancellation.

Result prefetching is a separate toggle, off by default, and respects Fast MKV
Startup rather than forcing it on. When enabled, it retains a session for up to two minutes, including its
parsed NZB and completed LRU articles. Expiring the eight-second warmup releases
leases rather than discarding completed cached data. A click starts a fresh app
startup clock (including any remaining wait on preparation) and records
`prepared_session_reused`; engine timestamps still include preparation time.
NZB metadata caching is independently toggleable and defaults on. It uses a
256 MiB app-private disk budget and a 14-day idle lifetime. The indexed format
avoids full XML reparsing and only inflates segment records for files actually
read. Bounded numeric startup hints survive session/process restarts; video data
and sessions do not. Turning it off clears the cache. See [NZB-CACHE.md](NZB-CACHE.md).

## Interpreting diagnostics

The headline covers Usenet resolution start through ExoPlayer's first rendered
frame. It excludes browsing, addon search, and selection before resolution.
The app records lock acquisition, engine readiness, session HTTP request and
response, resolved URL, player preparation, first load, tracks, decoder readiness,
decoder initialization duration, player readiness, and first frame.

Engine timestamps cover pool creation, NZB fetch/parse, content selection,
prefetch, and article readiness. The engine retains the first 24 HTTP range
observations within the first 30 seconds: offset, first-byte wait, bytes, and
time inside reads. Archive discovery and waiting for its shared mapping are
measured separately. These operations overlap, so their durations must not be
added to claim a total. NNTP establishment, TLS, authentication, downloading,
and yEnc decoding are included in observed waits, not separately attributed.

The cache card shows completed cache hits, joins of in-flight articles,
downloads, cancellations, and allocated article slabs. The engine snapshot is
requested just after the first frame; allocation is a snapshot, not peak RSS.
Reports omit URLs, credentials, message IDs, filenames, and payloads.

## Reproducing measurements

`TestMKVStartupMatrix` uses a generated H.264 MKV with end-of-file Cues, both
direct and in stored RAR volumes. Real TCP NNTP fixtures impose 100 Mbit/s with
40 ms RTT or 400 Mbit/s with 80 ms RTT. Six OFF and six ON trials alternate for
each case, using fresh sessions and article stores. It measures session opening,
64 KiB of head, the Cues range, and returning for a 2 MiB buffer. This engine
benchmark is not a time-to-first-frame measurement.

```powershell
$env:NUVIO_STARTUP_BENCH = '1'
$env:NUVIO_MKV_FIXTURE = 'C:\path\startup-fixture.mkv'
rtk go test ./internal/engine -run '^TestMKVStartupMatrix$' -count=1 -v -timeout=15m
```

The opt-in Android `UsenetStartupBenchmarkTest` uses the packaged native engine
and real ExoPlayer with an Android decoder. `TestStartupFixtureServer` supplies
the same four controlled NNTP cases through host port 28765. The emulator reads
the host as 10.0.2.2. Supply instrumentation arguments `startupBenchmark=true`
and optionally `startupTrials=6`. Reports are written to the debug app's external
files directory as `startup-benchmark.jsonl` and `startup-bootstrap.jsonl`.

The Android harness uses bare ExoPlayer and a warm Go process for playback
trials. Six separate cold/warm process-bootstrap pairs measure engine prewarm.
It does not exercise Nuvio navigation, the complete player UI, display refresh
switching, a physical TV, or a paid provider. The settings cards collect the
real app's startup on the user's device for that follow-up comparison.

`benchmarks/plot-startup.py` plots recorded trials only. Headline comparisons use
medians with individual points; stage stacks use means so each stack adds to its
measured mean total. Keep raw JSONL and CSV alongside the charts.

## Validation

Native tests cover bounded EBML parsing, direct and stored-RAR byte correctness,
head/Cues/head reuse, disabled/non-MKV/connection-limit gating, cancellation with
overlapping demand, memory-budget yielding, and session cleanup. The native
suite also runs under the Go race detector in WSL because the Windows race
runtime cannot reserve its address space on this host.

The Android sidecar integration tests cover packaging and lifecycle. The Compose
card test checks that timing and cache values are visible without activation and
that the remote-scrollable sections expose all rows.
