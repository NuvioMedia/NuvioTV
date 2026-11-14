package com.nuvio.tv.ui.screens.player.audiosync

/** One toast per result transition; refinements and cut changes do not repeat success. */
internal class AudioSyncResultToasts {
    private var lastResult: Boolean? = null

    fun reset() {
        lastResult = null
    }

    fun shouldShow(success: Boolean): Boolean {
        if (lastResult == success) return false
        lastResult = success
        return true
    }
}
