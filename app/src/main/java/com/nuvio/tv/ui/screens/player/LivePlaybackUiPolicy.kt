package com.nuvio.tv.ui.screens.player

/**
 * Live TV and VOD both commonly use HLS (.m3u8). The URL is not a reliable
 * discriminator. Use the player's live-window signal (ExoPlayer
 * [androidx.media3.common.Player.isCurrentMediaItemLive], or MPV unseekable)
 * plus the Stremio catalog type `channel`.
 *
 * Do not treat `tv` as live: in this app it is the series synonym.
 */
object LivePlaybackUiPolicy {
    fun isLiveContentType(contentType: String?): Boolean {
        return contentType.equals("channel", ignoreCase = true)
    }

    fun nextLiveLatch(playerReportsLive: Boolean, previouslyLatched: Boolean): Boolean {
        return previouslyLatched || playerReportsLive
    }

    /** First duration seen for [mediaKey] (0 = none yet) and whether it has grown since. */
    data class DurationGrowth(
        val mediaKey: String? = null,
        val firstDurationMs: Long = 0L,
        val grew: Boolean = false
    )

    /**
     * MPV can seek a live HLS stream inside its cache, so it reports it seekable; its duration,
     * though, is what it has loaded so far and keeps growing, while a VOD's stays put. Readings
     * taken before MPV reports the requested media are skipped: they can still be the previous
     * file's.
     */
    fun nextDurationGrowth(
        previous: DurationGrowth,
        mediaKey: String?,
        durationMs: Long,
        fromRequestedMedia: Boolean
    ): DurationGrowth {
        val current = if (previous.mediaKey == mediaKey) previous else DurationGrowth(mediaKey)
        if (!fromRequestedMedia || durationMs <= 0L || current.grew) return current
        if (current.firstDurationMs == 0L) return current.copy(firstDurationMs = durationMs)
        return if (durationMs > current.firstDurationMs + LIVE_DURATION_GROWTH_MS) current.copy(grew = true) else current
    }

    /** Growth past the first reading that marks a live stream (a VOD's duration only settles by a little). */
    const val LIVE_DURATION_GROWTH_MS = 10_000L

    fun isLivePlayback(
        playerReportsLive: Boolean,
        contentType: String?,
        latchedLive: Boolean = false
    ): Boolean {
        return latchedLive ||
            playerReportsLive ||
            isLiveContentType(contentType)
    }
}

/** Accumulates wall-clock time spent actually playing a live stream. */
class LivePlaybackWatchClock {
    private var accumulatedMs = 0L
    private var segmentStartedAtElapsedMs: Long? = null

    fun reset() {
        accumulatedMs = 0L
        segmentStartedAtElapsedMs = null
    }

    fun watchedDurationMs(
        isLive: Boolean,
        isPlaying: Boolean,
        nowElapsedMs: Long
    ): Long {
        if (!isLive) {
            if (accumulatedMs != 0L || segmentStartedAtElapsedMs != null) {
                reset()
            }
            return 0L
        }
        if (isPlaying) {
            if (segmentStartedAtElapsedMs == null) {
                segmentStartedAtElapsedMs = nowElapsedMs
            }
        } else {
            val started = segmentStartedAtElapsedMs
            if (started != null) {
                accumulatedMs += (nowElapsedMs - started).coerceAtLeast(0L)
                segmentStartedAtElapsedMs = null
            }
        }
        val running = segmentStartedAtElapsedMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
            ?: 0L
        return accumulatedMs + running
    }
}
