package com.nuvio.tv.ui.screens.player.iec

/**
 * Turns the raw 32-bit `AudioTrack.getPlaybackHeadPosition()` into a frame count since the last
 * flush.
 *
 * The raw counter is unsigned and is documented to return to zero on `flush()`. Some HALs keep
 * reporting the old count for a short while after the flush and only zero it once playback
 * restarts (seen on MediaTek mt8696: 8192 and 2832427 straight after a flush, then 0). Reading
 * that late drop as a 32-bit wrap puts the head 2^32 ahead of the written frames, and the sink's
 * clock then follows the written frames instead of the frames actually played.
 *
 * Rules:
 *  - between `onFlush()` and `onPlay()` the track is paused and flushed, so the head is 0 whatever
 *    the HAL reports;
 *  - the count the HAL still holds when playback resumes is a baseline, so a HAL that never zeroes
 *    its counter still reports progress since the flush;
 *  - a drop is a wrap only when the counter was in the top quarter of its range and lands in the
 *    bottom quarter (a real wrap needs 2^32 frames without a flush, 6.2 h at 192 kHz); any other
 *    drop is the HAL zeroing its counter late, and counting restarts from the new value.
 *
 * Not thread safe; call from the playback thread only, like the track it serves.
 */
internal class IecPlaybackHeadTracker {
    private var wrappedFrames = 0L
    private var lastRaw = 0L
    private var baseline = 0L
    private var awaitingPlay = false

    /** The track was paused and flushed. */
    fun onFlush() {
        wrappedFrames = 0L
        lastRaw = 0L
        baseline = 0L
        awaitingPlay = true
    }

    /** Playback is about to resume; [rawHead] is the raw counter read just before `play()`. */
    fun onPlay(rawHead: Int) {
        if (!awaitingPlay) return
        baseline = unsigned(rawHead)
        lastRaw = baseline
        awaitingPlay = false
    }

    /** Frames played since the last flush for the raw counter value [rawHead]. */
    fun frames(rawHead: Int): Long {
        if (awaitingPlay) return 0L
        val raw = unsigned(rawHead)
        if (raw < lastRaw) {
            if (lastRaw >= WRAP_FROM && raw < WRAP_TO) {
                wrappedFrames += 1L shl 32
            } else {
                wrappedFrames = 0L
                baseline = 0L
            }
        }
        lastRaw = raw
        return (wrappedFrames + raw - baseline).coerceAtLeast(0L)
    }

    private fun unsigned(rawHead: Int): Long = rawHead.toLong() and 0xFFFFFFFFL

    private companion object {
        const val WRAP_FROM = 0xC0000000L
        const val WRAP_TO = 0x40000000L
    }
}
