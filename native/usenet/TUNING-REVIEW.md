# Usenet tuning review — 2026-09-09

Decision: retain `AbortDrainBytes: 64 << 10` and the 12/24/32 MiB reader
limits. The proposed drain change eliminates ordinary-article reconnects, but
increases cold-seek latency substantially in the existing shared-link experiment.
The claimed byte-cap throttling cannot explain the cited Balanced smoke result
because that workload does not reach it. No runtime tuning behavior changed in
this review.

## Abandoned-body draining

The dependency's default is indeed 1 MiB. Its test is against the remaining
decoded part size, after cancellation, rather than the original article size.
An abandoned ordinary part can therefore be drained and its socket reused;
a 4 MiB part with more than 1 MiB remaining still causes a reconnect. See
`third_party/nntppool/nntp.go`, `defaultAbortDrainBytes` and the `abortDrain`
condition. Zero in `Provider.AbortDrainBytes` selects this default.

The old CSV's connection count is cumulative across first read, cold seek,
reader closure and sustained streaming. It is consistent with churn, but does
not by itself locate every reconnect at the seek. New phase counters show the
current Balanced configuration going from 16 connections after the first read
to 32 after the seek and 33 by the end of streaming. With a 1 MiB drain it stays
at 16 throughout, in every measured run.

However, a per-article drain threshold is not a limit on the total abandoned
window. Sixteen 768 KiB articles represent up to 12 MiB of decoded data sharing
one link, with encoding overhead and possible additional pipelined responses.
Draining them competes with the new playback position. A universal 20–40 ms
drain claim is unsupported: time depends on remaining bytes, per-connection
speed, shared bandwidth and queued responses.

The controlled comparison uses the same current engine, byte cap, production
provider construction and Balanced 16-connection / pipeline-4 / ahead-16
configuration. Only the drain threshold changes. There are six samples per
threshold per link, 24 successful transfers total, including a final repeat in
reverse threshold order. Results below are arithmetic means.

| Simulated link | Drain threshold | Cold seek | Sustained MiB/s | Total connections opened |
| --- | ---: | ---: | ---: | ---: |
| 100 Mbit/s, 40 ms RTT | 64 KiB | 336 ms | 11.24 | 33 |
| 100 Mbit/s, 40 ms RTT | 1 MiB | 1,522 ms | 11.16 | 16 |
| 400 Mbit/s, 80 ms RTT | 64 KiB | 277 ms | 32.67 | 33 |
| 400 Mbit/s, 80 ms RTT | 1 MiB | 471 ms | 31.72 | 16 |

Raw results: [windows-tcp-drain-review-2026-09-09.csv](benchmarks/windows-tcp-drain-review-2026-09-09.csv).
At 100 Mbit/s, individual 1 MiB-drain seeks ranged from 1,415 to 1,989 ms;
the 64 KiB runs ranged from 333 to 339 ms. At 400 Mbit/s, the ranges were
436–490 ms and 264–286 ms respectively. These observations support retaining
the shorter drain for this workload, not claiming a throughput improvement.

The matrix uses plain loopback TCP without authentication. It simulates BODY
RTT and bandwidth, but does not model TLS handshakes, real network connection
establishment or provider reconnect limits. The separate regression test uses
authenticated TCP and verifies exact bytes and socket reuse/replacement for
both 768 KiB and 4 MiB parts. Neither test establishes the best threshold for
a physical TV and paid TLS provider. Such measurements could favor a larger
drain; they are needed before claiming that reconnect avoidance wins overall.

## Reader byte limits

The smoke case reads ahead 16 articles of 768 KiB: 12 MiB decoded, with modest
yEnc overhead in the generated NZB wire estimates. This is below Balanced's
24 MiB reader limit. `FileReader.windowEnd` therefore stops at the segment
count. Removing the byte cap or increasing it to 48 MiB cannot enlarge this
workload's window.

The proposed approximately 480 ms figure describes transfer time at the link's
peak rate, rather than video playback duration; playback duration depends on
the media bitrate.

The historical fast-link Balanced baseline samples were 33.58 and 38.33 MiB/s,
averaging 35.96. The later samples were 31.06 and 31.15, averaging 31.11: about
13.5% lower using both samples, rather than an approximately 18% comparison
against only the faster baseline sample. The earlier latency review explicitly
records concurrent compiler/emulator activity during the later smoke run.
Those measurements are not an isolated test of the byte-cap change, and this
review does not establish the cause of their difference.

The two limits are also not interchangeable:

- The store's hard total article-slab budget is 64 MiB for Balanced. Its
  speculative admission threshold is 48 MiB of total reserved slabs, leaving
  room for demand. Allocation uses power-of-two slabs, not exact payload sizes.
- Store allocation happens in the yEnc metadata callback after a BODY request
  has reached the provider. A rejected speculative allocation prevents a slab
  allocation but does not prevent that network request. The active-request cap
  additionally bounds pending work, without predicting large article sizes.
- The reader byte cap bounds scheduling before those requests start, and also
  limits RAR boundary lookahead distance. Two readers can overlap there. Giving
  each reader Balanced's entire 48 MiB speculative allowance would permit
  96 MiB of estimated lookahead against one shared 64 MiB store.

Keeping the existing cap does not prove it optimal for large parts or unusual
manual settings. Those need a separate large-article/RAR workload measuring
throughput, discarded work and demand latency before changing the limits.

## Reproduction and validation

`TestPerformanceMatrix` now takes provider policy from `Providers`, records
connection counts after the first read and after the seek, and accepts an
opt-in drain override. With Go and CGO configured as in the README:

```powershell
$env:NUVIO_BENCHMARK = '1'
$env:NUVIO_BENCH_CASE = '16/4/16'
$env:NUVIO_BENCH_ABORT_DRAIN_BYTES = '65536'
rtk go test ./internal/engine -run '^TestPerformanceMatrix$' -count=3 -v -timeout 3m
$env:NUVIO_BENCH_ABORT_DRAIN_BYTES = '1048576'
rtk go test ./internal/engine -run '^TestPerformanceMatrix$' -count=3 -v -timeout 3m
Remove-Item Env:NUVIO_BENCHMARK, Env:NUVIO_BENCH_CASE, Env:NUVIO_BENCH_ABORT_DRAIN_BYTES
```

An unset override uses production policy; `0` selects the pool default. The raw
review CSV records the effective threshold, including the initial default runs.

The native `go test ./... -count=1` suite passed on Windows, including the new
`TestProviderSeekDrainPolicy`, existing byte/count cap coverage, large-article
seeks, concurrent ranges, RAR boundaries, cancellation and bounded allocation.
All 24 matrix transfers passed. No Android APK was rebuilt and no new race-test
result is claimed for this review.
