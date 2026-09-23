package com.nuvio.tv.ui.screens.player.iec

/**
 * Holds `AudioTrack.play()` back for a short settle time after `flush()`.
 *
 * On MediaTek mt8696 a `play()` that follows `pause()` + `flush()` too closely loses the flush: the
 * HAL keeps playing the audio that was buffered before the seek (about 0.9 s on the 1 s IEC track)
 * and queues the new audio behind it, so sound runs late against picture until the next flush.
 * Measured on a Fire TV Stick 4K Max: every lost flush came from a restart 47 to 84 ms after the
 * flush, restarts up to 127 ms sometimes still found it unsettled, and none later than that lost it.
 *
 * The gate only refuses; it never sleeps. The sink already calls `play()` again on every drain
 * while it is playing, so a refused start is retried within milliseconds, and data keeps being
 * written into the paused track meanwhile. Output does not begin until the track's start threshold
 * is filled in any case, which on the Fire TV Stick takes longer than the settle time.
 *
 * A freshly created track is not held: it has nothing buffered for a lost flush to replay.
 *
 * Not thread safe; call from the playback thread only, like the track it serves.
 */
internal class IecFlushSettleGate(
    private val settleNanos: Long = DEFAULT_SETTLE_NANOS,
    private val nanoTime: () -> Long = System::nanoTime
) {
    private var armed = false
    private var flushedAtNanos = 0L

    /** The track was paused and flushed just now. */
    fun onFlush() {
        armed = true
        flushedAtNanos = nanoTime()
    }

    /** True when `play()` may start the track now; false while the settle time is still running. */
    fun mayPlay(): Boolean {
        if (!armed) return true
        if (nanoTime() - flushedAtNanos < settleNanos) return false
        armed = false
        return true
    }

    companion object {
        const val DEFAULT_SETTLE_NANOS = 250_000_000L
    }
}
