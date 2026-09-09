package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C

// Matroska carries no per track bitrate element, so the rate is measured from the bytes the sink is
// handed. Only an encoded track can be measured; a decoded one hands the sink pcm, which would
// report the decoder output instead of the track.
internal object PlayerAudioBitrateMeter {

    // handleBuffer is single threaded, but reset runs from the player build and the overlay samples
    // from its own thread.
    @Volatile
    private var bytes: Long = 0L

    @Volatile
    private var firstPtsUs: Long = C.TIME_UNSET

    @Volatile
    private var lastPtsUs: Long = C.TIME_UNSET

    @Volatile
    private var publishedBps: Int = 0

    fun reset() {
        bytes = 0L
        firstPtsUs = C.TIME_UNSET
        lastPtsUs = C.TIME_UNSET
        publishedBps = 0
    }

    fun record(byteCount: Int, presentationTimeUs: Long) {
        if (byteCount <= 0 || presentationTimeUs == C.TIME_UNSET) return
        val last = lastPtsUs
        // A seek would otherwise divide the bytes by a stretch of timeline that carried none.
        if (last != C.TIME_UNSET &&
            (presentationTimeUs < last || presentationTimeUs - last > MAX_GAP_US)
        ) {
            bytes = 0L
            firstPtsUs = C.TIME_UNSET
        }
        if (firstPtsUs == C.TIME_UNSET) firstPtsUs = presentationTimeUs
        lastPtsUs = presentationTimeUs
        bytes += byteCount
        val spanUs = presentationTimeUs - firstPtsUs
        if (spanUs >= MIN_SPAN_US) {
            publishedBps = (bytes * 8.0 * 1_000_000.0 / spanUs).toInt()
        }
    }

    fun bitrateBps(): Int? = publishedBps.takeIf { it > 0 }

    // Below this the prefill burst has not averaged out and the row would visibly correct itself.
    private const val MIN_SPAN_US = 3_000_000L
    private const val MAX_GAP_US = 1_000_000L
}
