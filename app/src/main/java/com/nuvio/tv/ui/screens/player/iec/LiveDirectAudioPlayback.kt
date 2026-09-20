package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

internal object LiveDirectAudioPlayback {
    @Volatile
    private var passthroughLive: Boolean = false

    fun setPassthroughLive(live: Boolean) {
        passthroughLive = live
    }

    fun isPassthroughLive(): Boolean = passthroughLive

    fun resetForTest() {
        passthroughLive = false
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
