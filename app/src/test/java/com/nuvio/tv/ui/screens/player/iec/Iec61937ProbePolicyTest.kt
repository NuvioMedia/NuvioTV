package com.nuvio.tv.ui.screens.player.iec

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class Iec61937ProbePolicyTest {

    @After
    fun tearDown() {
        LiveDirectAudioPlayback.resetForTest()
    }

    @Test
    fun attempt_opensWhenNothingIsLive() {
        assertEquals(
            IecProbeAttemptAction.OPEN,
            iecProbeAttemptAction(
                usable = false,
                passthroughLive = false,
                ignoreLivePassthrough = false
            )
        )
    }

    @Test
    fun attempt_defersWhenPassthroughIsLive() {
        assertEquals(
            IecProbeAttemptAction.DEFER,
            iecProbeAttemptAction(
                usable = false,
                passthroughLive = true,
                ignoreLivePassthrough = false
            )
        )
    }

    @Test
    fun attempt_manualResetOpensEvenWhenPassthroughIsLive() {
        assertEquals(
            IecProbeAttemptAction.OPEN,
            iecProbeAttemptAction(
                usable = false,
                passthroughLive = true,
                ignoreLivePassthrough = true
            )
        )
    }

    @Test
    fun attempt_stopsWhenAlreadyUsable() {
        assertEquals(
            IecProbeAttemptAction.STOP_USABLE,
            iecProbeAttemptAction(
                usable = true,
                passthroughLive = true,
                ignoreLivePassthrough = false
            )
        )
    }

    @Test
    fun directFormat_trueForEncodedPassthrough_falseForPcm() {
        assertTrue(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_TRUEHD)))
        assertTrue(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_DTS_HD)))
        assertTrue(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_AC3)))
        assertTrue(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_E_AC3)))
        assertFalse(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_RAW)))
        assertFalse(LiveDirectAudioPlayback.isDirectPassthroughFormat(format(MimeTypes.AUDIO_AAC)))
    }

    private fun format(mime: String): Format {
        return Format.Builder().setSampleMimeType(mime).build()
    }
}
