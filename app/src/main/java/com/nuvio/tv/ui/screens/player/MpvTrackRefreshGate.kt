package com.nuvio.tv.ui.screens.player

import java.util.concurrent.atomic.AtomicLong

/**
 * Tells the progress tick whether the mpv track list must be re-read. [markStale] is called from
 * the mpv event thread; [isStale] and [onRefreshStarted] from the main thread.
 */
internal class MpvTrackRefreshGate {
    private val generation = AtomicLong(0L)
    private var refreshedGeneration = -1L

    val isStale: Boolean
        get() = generation.get() != refreshedGeneration

    fun markStale() {
        generation.incrementAndGet()
    }

    /** Call right before reading the tracks, so a change that lands mid-read keeps the gate stale. */
    fun onRefreshStarted() {
        refreshedGeneration = generation.get()
    }
}
