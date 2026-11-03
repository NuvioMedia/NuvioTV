package com.nuvio.tv.reshaped.livetv

import android.os.SystemClock
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player

/**
 * A paused or stalled live stream falls out of its playlist window (BEHIND_LIVE_WINDOW); the
 * player then only needs to jump back to the live edge. A few rejoins per minute at most, so a
 * stream that keeps failing still reaches Nuvio's own error handling. A replay the provider fails
 * (an HLS one whose parts 404) plays its TS link instead, once, see [replayFallback].
 */
object LiveEdgeRecovery {
    private const val MAX_REJOINS = 3
    private const val WINDOW_MS = 60_000L
    /** A provider's answer that another link for the same replay may not give (404 and the like). */
    private val REPLAY_LINK_ERRORS = intArrayOf(
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
    )

    private var windowStartMs = 0L
    private var rejoins = 0
    /** The stream the count is for: another channel starts with a fresh allowance. */
    private var stream: String? = null

    /**
     * Plays another link for the replay at the URL given, when it has one (the TS replay of an HLS
     * one); whether it did. Set while the Live TV player is open.
     */
    @Volatile var replayFallback: ((String) -> Boolean)? = null

    /** True when [error] was handled by rejoining the live edge of [player], or by another link for a replay. */
    fun tryRejoin(error: PlaybackException, player: Player?): Boolean {
        if (player == null) return false
        val playing = player.currentMediaItem?.localConfiguration?.uri?.toString()
        if (error.errorCode in REPLAY_LINK_ERRORS && LiveTvPlaybackRegistry.isCatchup(playing)) {
            return replayFallback?.invoke(playing!!) == true
        }
        if (error.errorCode != PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) return false
        val now = SystemClock.elapsedRealtime()
        if (playing != stream || now - windowStartMs > WINDOW_MS) {
            stream = playing
            windowStartMs = now
            rejoins = 0
        }
        if (rejoins >= MAX_REJOINS) return false
        rejoins++
        // A replay goes back to the earliest part it still has, not on to its end.
        if (LiveTvPlaybackRegistry.isCatchup(playing)) player.seekTo(0L) else player.seekToDefaultPosition()
        player.prepare()
        return true
    }
}
