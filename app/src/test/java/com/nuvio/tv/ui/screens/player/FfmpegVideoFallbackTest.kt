package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegVideoFallbackTest {

    private fun retry(
        alreadyRetried: Boolean = false,
        available: Boolean = true,
        decoderFailure: Boolean = true,
        failedRenderer: String? = "MediaCodecVideoRenderer",
        activeDecoder: String? = "c2.amlogic.vc1.decoder",
    ) = FfmpegVideoFallback.shouldRetryWithSoftwareDecoder(
        alreadyRetried = alreadyRetried,
        softwareDecoderAvailable = available,
        isDecoderFailure = decoderFailure,
        failedRendererName = failedRenderer,
        activeVideoDecoderName = activeDecoder,
    )

    @Test
    fun retriesPlatformVc1FailureOnce() {
        assertTrue(retry())
        assertTrue(retry(failedRenderer = null, activeDecoder = null))
        assertFalse(retry(alreadyRetried = true))
    }

    @Test
    fun noRetryWithoutFfmpegVc1Decoder() {
        assertFalse(retry(available = false))
    }

    @Test
    fun noRetryForNonDecoderErrors() {
        assertFalse(retry(decoderFailure = false, failedRenderer = null))
    }

    @Test
    fun noRetryWhenFfmpegItselfFailed() {
        assertFalse(retry(failedRenderer = FfmpegVideoFallback.RENDERER_NAME))
        assertFalse(retry(failedRenderer = null, activeDecoder = "ffmpeg7.0.3-vc1"))
    }

    @Test
    fun ffmpegDecoderNames() {
        assertTrue(FfmpegVideoFallback.isFfmpegVideoDecoderName("ffmpeg7.0.3-vc1"))
        assertFalse(FfmpegVideoFallback.isFfmpegVideoDecoderName("OMX.amlogic.vc1.decoder"))
        assertFalse(FfmpegVideoFallback.isFfmpegVideoDecoderName(null))
    }

    @Test
    fun withoutPlatformDecoders_hidesOnlyVc1() {
        val queried = mutableListOf<String>()
        val base = MediaCodecSelector { mimeType, _, _ ->
            queried += mimeType
            emptyList<MediaCodecInfo>()
        }
        val selector = FfmpegVideoFallback.withoutPlatformDecoders(base)

        assertEquals(emptyList<MediaCodecInfo>(), selector.getDecoderInfos(MimeTypes.VIDEO_VC1, false, false))
        selector.getDecoderInfos(MimeTypes.VIDEO_H264, false, false)
        selector.getDecoderInfos(MimeTypes.VIDEO_H265, true, false)

        assertEquals(listOf(MimeTypes.VIDEO_H264, MimeTypes.VIDEO_H265), queried)
    }
}
