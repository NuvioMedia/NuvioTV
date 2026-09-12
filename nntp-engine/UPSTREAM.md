# StreamNZB engine provenance and modifications

## Upstream source

The packages under `pkg/` and `third_party/rardecode/` were copied from
[Gaisberg/streamnzb](https://github.com/Gaisberg/streamnzb) at commit
`5097e2c1d490dc68d320cd8137334fdd209077ca`.

The Android playback dependency closure additionally backports these upstream
commits without importing StreamNZB's server, UI, indexer, or persistence
layers:

- `3206b697d`: detect articles omitted from an NZB instead of building a
  shifted virtual file;
- `937cbf334`: size read-ahead against the complete media stream;
- `402b1bb83`: use yEnc part geometry for exact segment maps and improve the
  decode-to-playback path;
- `96f0e83e9`: hand abandoned read-ahead work to the next range reader;
- `7b1a9c5dd` and `c945f1859`: bound consecutive missing-segment zero fills;
- `007e6b5cd6582edcc3cdbb3ce6b61603b6a66cbc`: rebuild a segment map when a
  decoded article disproves its estimated size.

Only the dependency closure required for NZB parsing, NNTP article fetching,
yEnc decoding, archive handling, and seekable media streaming is included.
Module versions used to build the native executable are pinned in `go.mod` and
`go.sum`.

## NuvioTV modifications

NuvioTV modifications began on 2026-09-01 and were last updated on 2026-09-12.
They comprise:

- a loopback HTTP API and Android entry point under `cmd/nuvio-nntp/`;
- Stremio `fileIdx` and `fileMustInclude` selection after archive metadata is
  available, implemented in `pkg/media/unpack/selection.go` and its RAR call
  site;
- removal of StreamNZB's persistent provider-health and usage-accounting hooks
  from the NNTP client pool, with session-scoped in-memory statistics instead;
- retention of completed read-ahead segments for the lifetime of a playback
  session, allowing backward seeks without fetching the articles again;
- initialization and Android logcat forwarding of sanitized engine diagnostics
  so missing articles, yEnc failures, and segment-map corrections are visible;
- provider validation during session creation, retaining the NNTP server's
  connection or authentication error instead of reporting only an unavailable
  pool;
- optional Android-side fallback across the next visible NZB results, with a
  configurable attempt limit, when NNTP session creation fails;
- bounded preflight validation of required release files during session
  creation, based on StreamNZB's playback verifier, so a definitive NNTP `430`
  is returned to Android before a loopback stream URL is published;
- startup optimizations that overlap provider authentication, NZB processing,
  archive planning, and preflight checks, retain authenticated provider
  connections briefly for fallback attempts, and avoid a redundant NZB byte
  copy; and
- Android-side engine prewarming with an idle timeout and phase timing logs;
- Android build and application integration code outside the copied packages.

## Licenses and binary distribution

The StreamNZB-derived packages and NuvioTV wrapper are distributed under the
[GNU General Public License v3.0](LICENSE). The vendored rardecode code remains
under its [BSD 2-Clause License](third_party/rardecode/LICENSE), including its
copyright and disclaimer. The combined native executable is distributed under
GPL-3.0 with the BSD notice retained.

Android builds package this file and both complete license texts under the
APK's `assets/licenses/` directory. The corresponding source is this repository,
including the pinned module versions and the build recipe below.

## Rebuilding the Android binaries

The checked-in binaries were built with Go 1.25.6, Android NDK 29.0.14206865,
and Android API level 24. From the repository root on Windows, run:

```powershell
.\nntp-engine\build-android.ps1 -NdkHome "$env:LOCALAPPDATA\Android\Sdk\ndk\29.0.14206865"
```

This rebuilds `libnuvionntp.so` for `arm64-v8a`, `armeabi-v7a`, `x86`, and
`x86_64`. Update the base commit and backport list, copied source, `go.mod`,
`go.sum`, this modification notice, toolchain versions, and Android binaries
together.
