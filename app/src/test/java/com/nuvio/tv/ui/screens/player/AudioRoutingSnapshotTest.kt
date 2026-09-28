package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioRoutingSnapshotTest {

    @Test
    fun iecActive_resolvesIecPassthrough() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_TRUEHD,
            sourceChannelCount = 8,
            isIecActive = true,
            isTranscodingAc3 = false,
            isAudioPathActive = false,
            sinkMime = MimeTypes.AUDIO_TRUEHD,
            sinkChannelCount = 8
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.PASSTHROUGH_IEC, routing?.mode)
        assertEquals("passthrough (IEC)", routing?.outputFormat)
        assertFalse(routing!!.isFallback)
    }

    @Test
    fun transcodeAc3_resolvesTranscodeAc3Fallback() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_TRUEHD,
            sourceChannelCount = 8,
            isIecActive = false,
            isTranscodingAc3 = true,
            isAudioPathActive = true,
            sinkMime = MimeTypes.AUDIO_AC3,
            sinkChannelCount = 6
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.TRANSCODE_AC3, routing?.mode)
        assertEquals("transcode AC-3 5.1", routing?.outputFormat)
        assertTrue(routing!!.isFallback)
    }

    @Test
    fun directPassthrough_resolvesDirectPassthrough() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_AC3,
            sourceChannelCount = 6,
            isIecActive = false,
            isTranscodingAc3 = false,
            isAudioPathActive = false,
            sinkMime = MimeTypes.AUDIO_AC3,
            sinkChannelCount = 6
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.PASSTHROUGH_DIRECT, routing?.mode)
        assertEquals("passthrough (direct)", routing?.outputFormat)
        assertFalse(routing!!.isFallback)
    }

    @Test
    fun surroundDecodedToStereoPcm_resolvesPcmFallback() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_TRUEHD,
            sourceChannelCount = 8,
            isIecActive = false,
            isTranscodingAc3 = false,
            isAudioPathActive = true,
            sinkMime = MimeTypes.AUDIO_RAW,
            sinkChannelCount = 2
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.PCM, routing?.mode)
        assertEquals("PCM 2.0 (fallback)", routing?.outputFormat)
        assertTrue(routing!!.isFallback)
    }

    @Test
    fun stereoAacDecodedToStereoPcm_resolvesNativePcm() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_AAC,
            sourceChannelCount = 2,
            isIecActive = false,
            isTranscodingAc3 = false,
            isAudioPathActive = false,
            sinkMime = MimeTypes.AUDIO_RAW,
            sinkChannelCount = 2
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.PCM, routing?.mode)
        assertEquals("PCM 2.0", routing?.outputFormat)
        assertFalse(routing!!.isFallback)
    }

    @Test
    fun multichannelFlacDecodedToMultichannelPcm_resolvesNativePcm() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = MimeTypes.AUDIO_FLAC,
            sourceChannelCount = 6,
            isIecActive = false,
            isTranscodingAc3 = false,
            isAudioPathActive = false,
            sinkMime = MimeTypes.AUDIO_RAW,
            sinkChannelCount = 6
        )
        assertNotNull(routing)
        assertEquals(AudioRoutingMode.PCM, routing?.mode)
        assertEquals("PCM 5.1", routing?.outputFormat)
        assertFalse(routing!!.isFallback)
    }

    @Test
    fun nullSourceMime_returnsNull() {
        val routing = resolveAudioRoutingSnapshot(
            sourceMime = null,
            sourceChannelCount = -1,
            isIecActive = false,
            isTranscodingAc3 = false,
            isAudioPathActive = false,
            sinkMime = null,
            sinkChannelCount = -1
        )
        assertNull(routing)
    }
}
