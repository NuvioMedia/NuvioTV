package com.nuvio.tv.ui.screens.player.iec

internal class IecPlaybackHeadTracker {
    private var wrappedFrames = 0L
    private var lastRaw = 0L
    private var baseline = 0L
    private var awaitingPlay = false

    fun onFlush() {
        wrappedFrames = 0L
        lastRaw = 0L
        baseline = 0L
        awaitingPlay = true
    }

    fun onPlay(rawHead: Int) {
        if (!awaitingPlay) return
        baseline = unsigned(rawHead)
        lastRaw = baseline
        awaitingPlay = false
    }

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
