package com.nuvio.tv.ui.screens.player

import android.os.SystemClock

/**
 * Measures genuine wall-clock viewing time, not playback position.
 *
 * Progress-based "watched time" is misleading: seeking forward inflates the
 * position without any extra viewing. This only accrues real elapsed time
 * while the player is actively playing, so seek jumps change nothing and
 * "10 minutes watched" really means 10 minutes on screen.
 */
internal class PlaybackWatchTracker {
    private var accumulatedMs = 0L
    private var segmentStartElapsedMs = 0L   // 0 == not currently accruing

    /** Call when playback transitions into an actively-playing state. */
    fun start() {
        if (segmentStartElapsedMs == 0L) {
            segmentStartElapsedMs = SystemClock.elapsedRealtime()
        }
    }

    /** Call when playback leaves the actively-playing state (pause/buffer/end/error). */
    fun stop() {
        val started = segmentStartElapsedMs
        if (started != 0L) {
            accumulatedMs += SystemClock.elapsedRealtime() - started
            segmentStartElapsedMs = 0L
        }
    }

    /** Current total, including any open segment. Pure (does not mutate). */
    fun totalWatchMs(): Long {
        val started = segmentStartElapsedMs
        return if (started != 0L) {
            accumulatedMs + (SystemClock.elapsedRealtime() - started)
        } else {
            accumulatedMs
        }
    }
}
