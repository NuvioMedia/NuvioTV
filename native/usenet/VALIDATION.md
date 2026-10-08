# Usenet test build validation

The new engine and Android integration have dedicated tests. Controlled benchmarks
and an Android TV emulator do not establish performance on a physical ARM TV or
a real paid provider. Those checks remain for device testing.

## Native checks

- Native Go suite passes: exact concurrent HTTP ranges, variable yEnc segment
  geometry, actual TCP pipelining, stored RAR4/RAR5, lazy season selection,
  anonymous RAR5 ordering, boundary lookahead/cancellation, shared article leases,
  bounded slabs, replay/late callbacks, subtitles and ECMAScript file selectors.
- Race-instrumented engine suite passes under Ubuntu WSL. The deepest pipeline
  configuration also passes three repeated runs (12 transfers in total).
- Buffered NNTP reply commitment regression passes in the vendored pool.
- Single-connection tests fill pipeline depths 2, 4 and 8 and verify exact payloads.
- All 36 TCP performance-matrix transfers pass; see benchmarks/*.csv and README.md.
- Released-article cache test proves later readers avoid repeat BODY downloads and
  new read-ahead evicts idle history without increasing the allocated slab budget.

## Wider application unit suite

The full JVM suite ran 1,171 tests: 1,152 passed, 18 failed and one was skipped.
The new UsenetStreamProtocolTest passed. Failures were in these existing classes:

- DefaultDataSourceRoutingTest (one socket-abort failure)
- LocalhostZeroCopyDataSourceTest (two socket/error-type failures)
- DefaultAllocatorTest (one released-allocation assertion)
- DolbyVisionBaseLayerPolicyTest (two policy assertions)
- FrameRateUtilsMkvSparseTracksTest and MatroskaAfrProbeTest (one stub-Cluster assertion each)
- CollectionsDataStoreSourceMigrationTest (one validation assertion)
- TraktAuthServiceTest (one refresh-call assertion)
- SimklMutationReconciliationTest (one history assertion)
- ContinueWatchingAiringRulesTest (one air-time assertion)
- NuvioExoPlayerPerformanceHelperTest (three RAM-tier/default assertions)
- PostPlayRecommendationStateTest (two state assertions)
- TrackSelectionInvestigationTest (one mock class-cast failure)

These are recorded rather than claiming a green full application suite. The
unchanged base checkout has not been rerun as a separate full baseline build.
Detailed Gradle XML and HTML reports remain in app/build/test-results and
app/build/reports/tests/testFullDebugUnitTest respectively.

## Android package checks

Both packaged Android integration tests passed on the Android 16 Television_1080p
x86_64 emulator. They verified native executable installation, addon resolution,
exact ExoPlayer HTTP Range and suffix bytes, lazy standalone subtitles, control
authentication, session deletion, daemon reuse, background cleanup and parent EOF.
The first final test invocation found no connected emulator; after rebooting it,
the unchanged APKs passed the connected test run (BUILD SUCCESSFUL).

The universal APK's Android v2 signature and ZIP/16 KiB alignment verify. All four
packaged engine binaries match the final native build SHA-256 hashes. Their ELF
CPU targets, Android interpreters and 16 KiB LOAD alignment were verified, and
all native dependency license notices are present in APK assets.

Installable files and SHA256SUMS.txt are copied into the workspace's artifacts
folder. Universal includes all four ABIs; ARM-specific APKs are smaller. Package:
com.nuviodebug.com; version 0.9.0-beta (1055); Android API 24 minimum.
