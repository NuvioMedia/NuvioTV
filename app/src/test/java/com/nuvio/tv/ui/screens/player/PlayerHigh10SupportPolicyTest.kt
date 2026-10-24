package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerHigh10SupportPolicyTest {
    @Test
    fun unsupportedHigh10_mustFailInsteadOfPlayingAudioOnly() {
        assertTrue(reject(format(MimeTypes.VIDEO_H264, "avc1.6e0028"), C.FORMAT_EXCEEDS_CAPABILITIES))
    }

    @Test
    fun handledHigh10_andOtherVideoFormats_keepExistingPlayback() {
        assertFalse(reject(format(MimeTypes.VIDEO_H264, "avc1.6e0028"), C.FORMAT_HANDLED))
        assertFalse(reject(format(MimeTypes.VIDEO_H264, "avc1.640028"), C.FORMAT_EXCEEDS_CAPABILITIES))
        assertFalse(reject(format(MimeTypes.VIDEO_H265, "hvc1.2.4.L120.B0"), C.FORMAT_EXCEEDS_CAPABILITIES))
    }

    @Test
    fun noCompatibleHigh10Decoder_isNotTransientRetry() {
        val format = format(MimeTypes.VIDEO_H264, "avc1.6e0028")
        val cause = MediaCodecRenderer.DecoderInitializationException(format, null, false, -49999)
        val error = ExoPlaybackException.createForRenderer(
            cause, "MediaCodecVideoRenderer", 0, format, C.FORMAT_EXCEEDS_CAPABILITIES,
            false, PlaybackException.ERROR_CODE_DECODER_INIT_FAILED
        )
        assertFalse(isRetryablePlaybackError(error))
    }

    private fun format(mime: String, codecs: String) =
        Format.Builder().setSampleMimeType(mime).setCodecs(codecs).build()

    private fun reject(format: Format, support: Int): Boolean =
        PlayerHigh10SupportPolicy.shouldReject(format, support)
}
