package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.Format
import com.nuvio.tv.ui.screens.player.PassthroughWaterLevelPacer

internal object LiveDirectAudioPlayback {
    @Volatile
    private var passthroughLive: Boolean = false

    @Volatile
    private var onPassthroughLiveCleared: (() -> Unit)? = null

    fun setPassthroughLive(live: Boolean) {
        val wasLive = passthroughLive
        passthroughLive = live
        if (wasLive && !live) {
            onPassthroughLiveCleared?.invoke()
        }
    }

    fun isPassthroughLive(): Boolean = passthroughLive

    fun setOnPassthroughLiveCleared(listener: (() -> Unit)?) {
        onPassthroughLiveCleared = listener
    }

    fun resetForTest() {
        passthroughLive = false
        onPassthroughLiveCleared = null
    }

    fun isDirectPassthroughFormat(format: Format): Boolean {
        return PassthroughWaterLevelPacer.isPassthroughMime(format.sampleMimeType)
    }
}
