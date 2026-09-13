# Native Usenet engine provenance

NuvioTV's native engine adapts the shared progressive article/flight model,
NZB planning, lazy archive strategy, RAR volume naming and real NNTP benchmark
harness from [AltMount](https://github.com/kipsilabs/altmount), commit
`3fd79fad97772d193b6916e316753b9a267c6520` (MIT, Javier Blanco).
The original license is in `licenses/AltMount-MIT.txt` and is packaged in the APK.

Dependencies and adaptations:

| Source | Version | Use / local changes |
| --- | --- | --- |
| javi11/nntppool | v4.23.0, MIT | Vendored pipelined NNTP pool. Commit buffered replies before writing decoded bytes; suppress callbacks for abandoned attempts; join the reader before reusing a connection's slab. Regression test included. |
| javi11/nzbparser | v0.5.5, MIT | Subject parsing; XML handling adapted to decode one NZB file at a time and preserve addon file indices. |
| javi11/rardecode | v2.2.4, BSD | RAR4/RAR5 header fields and stored-data extent mapping adapted from archive15.go, archive50.go and archive_info.go. No decompression code is included in the engine. |
| mnightingale/rapidyenc | 7aafef1eaf1c, MIT | Chunked yEnc wrapper. Android link targets added; upstream desktop archives retained for host tests. |
| animetosho/rapidyenc | 480bd7b5896f8b3edecc721d23f1384d767ffe2f, public domain / CC0 | Native SIMD kernels compiled from source for every APK ABI. CRC component disabled; Go CRC32 validates articles. |
| dlclark/regexp2 | v2.7.2, MIT | ECMAScript addon file selectors, with bounded matching time, backtracking stack and retained filename buffers. |

AltMount's stored archive fixtures and NNTP test server are included under its
MIT license. `third_party` retains upstream source notices. `licenses` is an APK
assets source directory; Android packages these notices with the engine.

NuvioTV base checkout: `23d1fe478e380860dae3eb41c8770533361a0cc5`.
