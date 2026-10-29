# Anime season-pack episode investigation

Status: **the reported E01 to E11/E12 mismatch is not reproduced on the tested `dev` checkout; its root cause is not established. No playback behavior fix has been made.**

## Report and reproduction

The user reports that selecting E01 from an AnimeSub+ season pack played E11 or E12 in Nuvio while the same release played E01 in Stremio. Native “Resolve playable links” was disabled. The report mentions both EMBER and Judas packs and both TorBox and Real-Debrid; the original device, Nuvio version, content identifier, and exact E01 response from that installation remain unknown.

The concrete source supplied for investigation identifies *Vivy: Fluorite Eye’s Song*, S01E04, from `[EMBER] Vivy: Fluorite Eye`s Song (2021) (Season 1 + Special) [BDRip] [1080p Dual Audio HEVC 10 bits]`. The supplied E04 was confirmed by the user to work. The authorized addon configuration was then queried for the **same pack** and S01E01 using IMDb content ID `tt13851958`. The addon manifest identified AnimeSub+ version `1.17.151`; the selected result displayed `TB` (TorBox). Base commit: `d14832e1c477cca2b5613b893e590fd5fcab83ee`; variant: `fullDebug`, `com.nuviodebug.com`. A fresh debug guest profile has no configured native debrid credentials and the resolver is disabled.

The torrent info hash in this public release is `4f63ab726f514584597ee07b3fb794915838f48b`. No addon credentials, private URLs, signed redirect targets, request headers or user identifiers are recorded here. The full addon response and resolver links are stored separately in a user-private local directory and are not Git files.

## Observed path and evidence

The captured E01 `/stream/series/...` result has an HTTPS resolver `url`, `behaviorHints.filename` and no top-level `infoHash`, `fileIdx`, or `clientResolve`. The resolver URL names the E01 file and carries `fileIndex=10` as an addon-owned query parameter. The E11 URL carried `fileIndex=0` when queried; these values are not safely inferred from episode order. Nuvio's native file selectors cannot explain this observed direct-URL case while resolution is disabled.

Upstream commit `71632b927` (2026-09-29) already changed native debrid file selection; it is present in this checkout. Its applicability to the original report is unproven, and it is bypassed for the captured direct URL when resolution is disabled.

The code path at this commit is: addon request in `StreamRepositoryImpl.getStreamsFromAddon` → `StreamDto` → `StreamMapper.toDomain` → `StreamScreenViewModel.resolveStreamForPlayback` and `getStreamForPlayback` → `NuvioNavHost` navigation → `PlayerNavigationArgs` / `PlayerRuntimeController` → `PlayerMediaSourceFactory` / Media3. The direct URL is used when native debrid resolution is not applicable. A torrent-only response or a different addon response can take another path and needs its own trace.

Temporary debug logging recorded only SHA-256 fingerprints and non-secret flags. The same fingerprint appeared for the captured E01 URL at the raw addon response, mapped stream, playback handoff, player initialization and Media3 source. Handoff logged season 1, episode 1, no top-level file index, no torrent and no client resolver. An independent E01 addon request produced the same URL fingerprint. Actual fingerprints are intentionally omitted from this document. The logging was temporary and removed after collecting a local redacted trace at `/home/schubmma/.local/share/nuvio-dev/safe-episode-trace.log`; the emulator's other log tags were suppressed because upstream debug logs can contain private URLs.

The E01 resolver returned HTTP 302 and a ranged media response with an E01 Content-Disposition filename for both tested Nuvio and Stremio user agents. `ffprobe` reported Matroska title `S01E01-EMBER`, duration 1572.096 seconds. In the Android TV emulator Media3 received this URL but rejected the 10-bit HEVC video with `ERROR_CODE_DECODING_FAILED`. Nuvio's MPV fallback rendered the stream; its on-screen duration was 26:12, consistent with the independently inspected E01 file. This is evidence for the current test case, not a reproduction of the reported mismatch on the original device or a visual comparison against Stremio.

The same pack produced Matroska metadata `S01E02-EMBER`, `S01E11-EMBER`, and `S01E12-EMBER` in independent `ffprobe` checks. In the emulator the corresponding selected URLs matched the addon responses at the handoff, and MPV rendered each after the Media3 decoder failure. The on-screen durations matched the inspected files: E02 22:45, E11 and E12 23:41. Selecting E01 again after E12 still handed off the independently captured E01 URL. These checks do not prove playback in both clients or both debrid services.

## Tests and limitations

- The focused suite passed 49 tests: the captured-response test and existing shared selector tests for TorBox, Real-Debrid and Premiumize, including standard episode matching, exact names, ambiguity, and invalid indexes.
- `AnimeSubStreamMappingTest` uses a sanitized captured E01 response to check preservation of its direct URL and filename through DTO mapping, player arguments, and normalization. It is a characterization test; it does not reproduce the reported failure or prove a fix.
- The full unit suite ran 2,365 tests: 7 failed and 1 was skipped. Two failures were in Trakt tests and five in `LibassOverlayGeometryTest`; all seven also failed when those classes were rerun separately. These failures are outside the direct-stream changes under investigation.
- The original Nuvio/Stremio side-by-side test, original device and settings, Real-Debrid, Judas release, other packs, single-file playback, and visual comparison against Stremio remain unverified. An independently inspected single-file E02 result had E02 Matroska metadata, but its playback was not tested. The emulator's Media3 decoder limitation prevents verifying this HEVC release with Media3 on this device.

To isolate the actual failure, obtain the original Nuvio version/device, metadata addon and requested content ID, provider for each test, and a sanitized E01 addon response or a private local copy of the exact E01 resolver URL from the failing installation. Compare its URL fingerprint at the same boundaries, its response metadata and its final media content against Stremio. Do not send or log tokens or signed URLs. Only implement a fix after the differing boundary and a reproducible regression are identified.
