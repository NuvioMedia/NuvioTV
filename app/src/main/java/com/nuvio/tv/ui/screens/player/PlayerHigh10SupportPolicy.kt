package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import com.nuvio.hi10video.AvcHigh10ProfileDetector

@UnstableApi
internal object PlayerHigh10SupportPolicy {
    fun shouldReject(format: Format, @C.FormatSupport support: Int): Boolean =
        AvcHigh10ProfileDetector.isHigh10(format) && support != C.FORMAT_HANDLED

    fun hasNoCompatibleDecoder(error: PlaybackException): Boolean {
        val format = (error as? ExoPlaybackException)?.rendererFormat ?: return false
        if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED ||
            !AvcHigh10ProfileDetector.isHigh10(format)
        ) return false
        var cause = error.cause
        while (cause != null) {
            if (cause is MediaCodecRenderer.DecoderInitializationException) {
                return cause.codecInfo == null
            }
            cause = cause.cause
        }
        return false
    }
}
