package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C

internal object PlayerAudioBitrateMeter {

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

    private const val MIN_SPAN_US = 3_000_000L
    private const val MAX_GAP_US = 1_000_000L
}
