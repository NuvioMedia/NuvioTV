package com.nuvio.tv.reshaped.livetv

/**
 * Stream URLs Live TV has sent to the player, so the fork's playback extras (disk read-ahead,
 * connection speed learning) leave live channels alone, and the player overlay knows it is
 * showing a channel. Also remembers which list entry each one came from: a Stalker link is
 * created per play and differs from the list's URL. Reads are memory lookups.
 */
object LiveTvPlaybackRegistry {
    private const val MAX_URLS = 16

    /** Playback URL to the list entry's URL, most recent last. */
    @Volatile private var entries: List<Pair<String, String>> = emptyList()
    /** The registered URLs that are a past programme (catch-up), not the live channel. */
    @Volatile private var catchups: Set<String> = emptySet()
    /** For a catch-up URL, the time it plays: [LiveTvReplayWindow]. */
    @Volatile private var windows: Map<String, LiveTvReplayWindow> = emptyMap()

    @Synchronized
    fun register(playbackUrl: String, listUrl: String = playbackUrl, catchup: Boolean = false, window: LiveTvReplayWindow? = null) {
        if (playbackUrl.isBlank()) return
        entries = (entries.filterNot { it.first == playbackUrl } + (playbackUrl to listUrl)).takeLast(MAX_URLS)
        val kept = entries.mapTo(HashSet()) { it.first }
        catchups = catchups.filterTo(HashSet()) { it in kept && it != playbackUrl }.apply { if (catchup) add(playbackUrl) }
        windows = windows.filterTo(HashMap()) { (url, _) -> url in catchups && url != playbackUrl }
            .apply { if (catchup && window != null) put(playbackUrl, window) }
    }

    /** The time the catch-up at [url] plays, when it was registered with one. */
    fun replayWindow(url: String?): LiveTvReplayWindow? = url?.let { windows[it] }

    /** Whether [url] plays a past programme: it can be paused and sought like a film. */
    fun isCatchup(url: String?): Boolean = url != null && url in catchups

    fun isLiveTv(url: String?): Boolean = url != null && entries.any { it.first == url }

    /** The list entry's URL for a URL the player is playing (itself when unknown). */
    fun listUrlFor(playbackUrl: String?): String? =
        playbackUrl?.let { url -> entries.lastOrNull { it.first == url }?.second ?: url }
}

/**
 * What a replay link plays: from [startMs] (where its position 0 is) to [endMs]. A link that
 * names no end ([bounded] false: `?utc=` and the like) plays on to live by itself.
 */
class LiveTvReplayWindow(val startMs: Long, val endMs: Long, val bounded: Boolean = true)
