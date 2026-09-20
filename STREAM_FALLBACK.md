# Automatic Usenet fallback

Enable **Automatic Usenet fallback** in Usenet settings to try up to five following
Usenet sources when an NZB fails during preparation or playback. The setting is
device-local, persists across restarts, and is off by default. The selected source
and its replacements share one attempt budget across selection and playback.

- Uses the displayed, filtered source order. Never wraps to earlier entries.
- Only Usenet selections can trigger fallback, and only Usenet replacements are
  eligible. Torrent, debrid HTTP, and other streams report their normal failure
  even with the setting enabled.
- Playback fallback is bound to the resolved Usenet URL. Later torrent or HTTP
  selections cannot reuse an earlier Usenet queue.
- Deduplicates by playback identity, including file selection and request headers;
  changing an addon label or title does not create a new attempt.
- Keeps the playback position and paused state when replacing a failed playing source.
- Propagates cancellation. Back, a new selection, or backgrounding during automatic
  recovery stops that recovery.
- Gives each preparation at most 120 seconds. No generic source search runs when
  a player has no Usenet queue. Existing player network/codec recovery runs first.
- Turning the setting off prevents further replacements, including a replacement
  still being prepared.
- Handles explicit MPV end-file errors. Natural completion, Stop, and redirects do
  not trigger fallback. Playback in external apps is outside this recovery path.
- Each episode starts a new queue using that episode's results.

The implementation was informed by [Kernexshadow's NZB fallback policy](https://github.com/kernexshadow/NuvioTV/blob/nntp/app/src/main/java/com/nuvio/tv/core/usenet/NntpFallbackPolicy.kt).

## Device checks

1. With the toggle off, select a broken NZB followed by a working one; confirm failure
   without switching. Enable the toggle and repeat; confirm the next NZB plays,
   skipping any intervening torrent or HTTP entries.
2. Fail a normal torrent or HTTP source with the toggle both off and on; confirm
   no automatic source switch in either case.
3. Fail a Usenet source halfway through playback; confirm the replacement resumes near
   the saved position, including when paused.
4. Supply only broken NZBs; confirm at most five replacements and an actionable error.
5. Press Back or select another source during resolution; confirm no delayed navigation.
6. Trigger an MPV load error on a Usenet source; confirm fallback. Play to the natural end; confirm
   normal next-episode behavior instead.
7. Autoplay the next episode with a broken first NZB; confirm every replacement
   belongs to the new episode.
8. Restart the app and confirm the toggle retains its saved value.

Unit coverage lives in `StreamFallbackSessionTest`, `UsenetSettingsTest`, and
`MpvPlaybackFailureTest`.
