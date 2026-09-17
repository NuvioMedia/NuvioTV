# Indexed NZB cache

The previous disk-hit path reopened the saved NZB, decompressed the entire gzip
stream when present, and ran the complete XML parser. A season pack paid that
cost for every episode. The new cache retains the complete playback metadata in
a normalized format, with random access to each file's segment list.

## Layout and lookup

Each request still maps to a SHA-256 key over its URL, effective headers and
profile scope. Request credentials and URLs are not saved alongside the entry.
The private `<key>.nzb` container contains:

1. A 56-byte header: format/version magic, directory offset and length, and a
   SHA-256 checksum of the directory.
2. One independent gzip member per NZB file, encoding sorted message IDs and
   wire sizes as compact binary records.
3. A plain JSON directory containing original XML file indexes, filenames,
   release order, estimated sizes, segment counts, record offsets/lengths,
   decoded byte limits and per-record SHA-256 checksums.

Lookup reads the header and directory. It creates fresh session-owned `File`
objects without materializing segment lists. The existing selector operates on
names, indexes and estimated sizes. The first reader of a selected file loads
its record once, builds its prefix weights, and restores any valid layout hints.
Other episodes and subtitles remain unloaded; RAR volumes load as discovery or
playback reaches them. A successful cold cache fill also switches to this lazy
representation so it does not retain the entire parsed season pack in RAM.

This design replaces the original XML rather than keeping duplicate XML and
parsed copies. It preserves everything the playback engine uses, including
indexes with gaps caused by empty XML file entries. Poster, group, date and
comment fields are not retained. The cache is disposable private application
state, not an archival/export representation of the source NZB.

No additional plain segment-list hot cache is maintained. Independent compact
records make the normal season-pack hit small enough to read and decode in
about two milliseconds in the final host fixture, without another copy, eviction policy
or synchronization scheme. Work scales with the selected file's segment count,
not the entire NZB. An unusually large single file still loads its whole segment
table; this is not a per-segment paging implementation.

## Learned startup metadata

`<key>.hints` is a checksummed, versioned sidecar bound to the directory digest.
Its maximum size is 128 KiB, with these additional limits:

- Up to 32 recently used files, each with at most 128 decoded segment anchors,
  decoded file size and a recovered filename. Sampling favors head/tail anchors
  and keeps a sparse selection through the rest of the learned layout.
- Up to 16 recently used Cues offsets. Content identity includes the source
  file record digest, logical filename, logical size and first RAR data offset,
  so different stored entries do not share a seek pointer.

Hints are saved after useful startup work and on store close, rather than on
each article. Concurrent sessions merge their latest hints before replacement.
They share the existing disk budget, idle lifetime and parent-entry eviction.
Corrupt, oversized, incompatible or geometrically invalid hints are ignored.

Restored anchors remain distinguishable from live yEnc metadata. Conflicts
discard restored anchors while preserving live anchors; conflicting live
metadata still fails validation. Selection continues its normal first-article
probe to establish the decoded content size before returning the session URL.

A restored Cues pointer feeds the existing bounded warmup immediately. It avoids
the SeekHead scan and the index worker's 80 ms wait/fallback heuristic. The Cues
article and its successor still come from NNTP and use the existing RAM cache.
`cues_cache_hit` records this reuse in engine startup marks.

Only numbers, identifiers and filenames are serialized. No article bodies,
video data, raw MKV/RAR headers, Cues bytes, or extracted subtitle payloads are
written. Predicted RAR extents are not persisted: intermediate headers still
receive the existing validation when accessed.

## Failure handling and bounds

The 256 MiB cache budget includes document records, hint sidecars and temporary
writes. Indexed entries retain the 64 MiB per-document limit and 256-document
limit. A valid hit renews the 14-day idle lifetime. Least recently used entries
are evicted with their hints; active descriptors are pinned until store close.
If pinned entries leave insufficient room, optional cache writes are skipped.

The directory is capped at 4 MiB and decoded incrementally with the existing
10,000-file and 500,000-segment limits. Record ranges, byte counts and size
arithmetic are checked before allocations and reads. Gzip checksums and record
digests are verified for each loaded file. This avoids scanning the entire
entry merely to validate an unrelated episode.

Directory errors fall back to the source fetch. A corrupt record found during
a lazy read triggers one shared download/parse recovery for that session. The
requested file's metadata digest must match before recovery can supply it;
changed source metadata requires reopening rather than mixing generations.
The corrupt entry is removed after its last active descriptor closes. A normal
session cancellation does not invalidate a healthy entry.

Legacy XML and gzip entries migrate after their first successful parse without
another download. Cache writes use temporary files followed by rename;
incomplete files and orphan hints are cleaned up. Disk failures do not fail
the initial download/playback path. Disabling caching preserves the existing
bypass/clear behavior.

## Validation and measurement

`nzb_index_test.go` covers lazy episode/subtitle/RAR reads, exact range bytes,
cold-open memory retention, concurrent corruption recovery, changed-source
rejection, legacy migration, directory bounds, hint corruption/generation
checks, stale-anchor correction, cross-session hint merging, lifecycle cleanup,
disk accounting, and persisted MKV warmup without payload artifacts.

The benchmark uses 160,000 segments, either across 100 files or in one extreme
single file. It compares XML parsing, gzip XML parsing, and a fresh cache lookup
plus materializing the selected file. Each indexed iteration creates and closes
a fresh store; it does not reuse a parsed in-memory file table. The OS page cache
is not flushed. Synthetic message IDs have a long common suffix, so compression
ratios should not be treated as predictions for arbitrary indexers.

Run from `native/usenet` with the repository's Go/CGO toolchain:

```sh
go test ./...
go test -race ./internal/engine
go test ./internal/engine -run '^$' -bench BenchmarkNZBCachedStartup -benchtime=5x -count=1 -v
```

Final host results (2026-09-15, Windows amd64, Go 1.27.1, i7-1260P, five
iterations per case):

| Fixture | XML parse | Gzip XML parse | Indexed lookup + selected file | Indexed allocation per open |
| --- | ---: | ---: | ---: | ---: |
| 100 files, 1,600 segments each | 964.0 ms | 959.9 ms | 1.99 ms | 0.57 MiB |
| One file, 160,000 segments | 886.6 ms | 898.2 ms | 76.21 ms | 40.41 MiB |

The season fixture occupies 38,809,501 XML bytes, 894,267 gzip XML bytes, or
543,969 indexed-cache bytes. The single-file fixture occupies 39,297,867 XML
bytes, 883,120 gzip XML bytes, or 516,544 indexed-cache bytes. The whole NZB is
retained in compressed playback-metadata form even though only one file's
segment list is materialized. Raw output: [nzb-cache-2026-09-15.txt](benchmarks/nzb-cache-2026-09-15.txt).

Validation completed on the final source:

- Full native `go test ./...` suite on Windows.
- Complete engine test suite with Go's race detector, cross-compiled and run in
  Ubuntu WSL from the package directory so upstream RAR fixtures resolve.
- Native Gradle builds for arm64-v8a, armeabi-v7a, x86 and x86_64. All four
  checked-in prebuilts match the generated engine binaries.
- `git diff --check`.

These are metadata timings, not physical-TV or real-provider time-to-first-frame
measurements. Network connection, first-article download, decoder setup and RAR
header discovery remain separate costs. No new device playback benchmark was
performed for this change.
