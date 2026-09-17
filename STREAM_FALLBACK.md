# Automatic stream fallback

When stream preparation fails, or playback exhausts the existing same-source recovery,
Nuvio tries up to five subsequent distinct sources. The selected source plus those
five replacements share one attempt budget across source selection and playback.

- Uses the displayed, filtered source order. Never wraps to earlier entries.
- Supports HTTP, Usenet, torrents, and debrid preparation. Skips browser-only links,
  YouTube entries, and sources without a supported playback target.
- Deduplicates by playback identity, including file selection and request headers;
  changing an addon label or title does not create a new attempt.
- Keeps the playback position and paused state when replacing a failed playing source.
- Propagates cancellation. Back, a new selection, or backgrounding during automatic
  recovery stops that recovery.
- Gives each preparation at most 120 seconds. Source discovery for cached/deep links
  is limited to 30 seconds. Existing player network/codec recovery runs first.
- Handles explicit MPV end-file errors. Natural completion, Stop, and redirects do
  not trigger fallback. Playback in external apps is outside this recovery path.
- Each episode starts a new queue using that episode's results. No settings changes
  or extra confirmation are needed.

The implementation was informed by [Kernexshadow's NZB fallback policy](https://github.com/kernexshadow/NuvioTV/blob/nntp/app/src/main/java/com/nuvio/tv/core/usenet/NntpFallbackPolicy.kt).

## Device checks

1. Select a broken NZB followed by a working one; confirm automatic preparation and playback.
2. Play an HTTP source returning 404 followed by a working source; confirm fallback
   after existing recovery, with an attempt message and the new source indicator.
3. Fail a source halfway through playback; confirm the replacement resumes near
   the saved position, including when paused.
4. Supply only broken sources; confirm at most five replacements and an actionable error.
5. Press Back or select another source during resolution; confirm no delayed navigation.
6. Trigger an MPV load error; confirm fallback. Play to the natural end; confirm
   normal next-episode behavior instead.
7. Autoplay the next episode with a broken first source; confirm every replacement
   belongs to the new episode.

Unit coverage lives in `StreamFallbackSessionTest` and `MpvPlaybackFailureTest`.
