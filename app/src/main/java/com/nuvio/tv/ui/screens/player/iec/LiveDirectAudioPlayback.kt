package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

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
        val mime = format.sampleMimeType ?: return false
        if (mime == MimeTypes.AUDIO_RAW) return false
        return mime == MimeTypes.AUDIO_E_AC3 ||
            mime == MimeTypes.AUDIO_E_AC3_JOC ||
            mime == MimeTypes.AUDIO_AC3 ||
            mime == MimeTypes.AUDIO_AC4 ||
            mime == MimeTypes.AUDIO_TRUEHD ||
            mime == MimeTypes.AUDIO_DTS ||
            mime == MimeTypes.AUDIO_DTS_HD ||
            mime == MimeTypes.AUDIO_DTS_EXPRESS ||
            mime == MimeTypes.AUDIO_DTS_X ||
            mime.startsWith("audio/vnd.dts")
    }
}
