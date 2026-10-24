package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughOpenRetryPolicyTest {

    private val initFailed = PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED

    @Test
    fun twoRetriesPerStream_withGrowingDelay_thenNone() {
        val policy = PassthroughOpenRetryPolicy()
        assertEquals(400L, policy.nextDelayMs("a"))
        assertEquals(800L, policy.nextDelayMs("a"))
        assertNull(policy.nextDelayMs("a"))
        assertNull(policy.nextDelayMs("a"))
    }

    @Test
    fun aDifferentStream_startsWithAFullBudget() {
        val policy = PassthroughOpenRetryPolicy()
        policy.nextDelayMs("a")
        policy.nextDelayMs("a")
        assertEquals(400L, policy.nextDelayMs("b"))
    }

    @Test
    fun anOpenedTrack_restoresTheBudget() {
        val policy = PassthroughOpenRetryPolicy()
        policy.nextDelayMs("a")
        policy.nextDelayMs("a")
        policy.onAudioTrackOpened()
        assertEquals(400L, policy.nextDelayMs("a"))
    }

    @Test
    fun aRefusedBitstreamOpen_isEligible() {
        for (mime in listOf(
            MimeTypes.AUDIO_AC3,
            MimeTypes.AUDIO_E_AC3,
            MimeTypes.AUDIO_E_AC3_JOC,
            MimeTypes.AUDIO_TRUEHD,
            MimeTypes.AUDIO_DTS,
            MimeTypes.AUDIO_DTS_HD,
            MimeTypes.AUDIO_DTS_X
        )) {
            assertTrue(mime, PassthroughOpenRetryPolicy.isEligible(initFailed, mime, false, false))
        }
    }

    @Test
    fun aRefusedPcmOpen_isNotRetried() {
        assertFalse(PassthroughOpenRetryPolicy.isEligible(initFailed, MimeTypes.AUDIO_RAW, false, false))
        assertFalse(PassthroughOpenRetryPolicy.isEligible(initFailed, null, false, false))
    }

    @Test
    fun aFormatThePolicyDenies_isNotRetried() {
        assertFalse(PassthroughOpenRetryPolicy.isEligible(initFailed, MimeTypes.AUDIO_DTS_HD, true, false))
    }

    @Test
    fun afterThePcmFallback_nothingIsRetried() {
        assertFalse(PassthroughOpenRetryPolicy.isEligible(initFailed, MimeTypes.AUDIO_TRUEHD, false, true))
    }

    @Test
    fun otherErrors_areNotRetried() {
        assertFalse(
            PassthroughOpenRetryPolicy.isEligible(
                PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED,
                MimeTypes.AUDIO_TRUEHD,
                false,
                false
            )
        )
        assertFalse(
            PassthroughOpenRetryPolicy.isEligible(
                PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
                MimeTypes.AUDIO_TRUEHD,
                false,
                false
            )
        )
    }

    @Test
    fun theSinkInputFormat_isReadFromTheInitializationFailure() {
        val sinkInput = Format.Builder().setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()
        val initialization = AudioSink.InitializationException("refused", 0, sinkInput, false, null)
        val wrapped = RuntimeException("renderer", RuntimeException("sink", initialization))
        assertEquals(MimeTypes.AUDIO_E_AC3, failedAudioTrackInputFormat(wrapped)?.sampleMimeType)
    }

    @Test
    fun anErrorWithoutAnInitializationFailure_hasNoSinkInputFormat() {
        assertNull(failedAudioTrackInputFormat(RuntimeException("decoder")))
        assertNull(failedAudioTrackInputFormat(null))
    }

    @Test
    fun retryWaitsWhileTheHdmiOutputIsGone() {
        assertFalse(PassthroughOpenRetryPolicy.outputReady(emptySet(), routeIsHdmi = false))
    }

    @Test
    fun retryGoesOnceTheOutputTakesBitstreamOrMediaIsOnHdmi() {
        val trueHd = setOf(androidx.media3.common.C.ENCODING_DOLBY_TRUEHD)
        val ac3 = setOf(androidx.media3.common.C.ENCODING_AC3)
        assertTrue(PassthroughOpenRetryPolicy.outputReady(trueHd, routeIsHdmi = false))
        assertTrue(PassthroughOpenRetryPolicy.outputReady(ac3, routeIsHdmi = false))
        assertTrue(PassthroughOpenRetryPolicy.outputReady(emptySet(), routeIsHdmi = true))
    }

    @Test
    fun theWaitForTheOutputIsBounded() {
        val max = PassthroughOpenRetryPolicy.MAX_OUTPUT_WAIT_MS
        assertFalse(PassthroughOpenRetryPolicy.mayRetryNow(false, max - 1))
        assertTrue(PassthroughOpenRetryPolicy.mayRetryNow(false, max))
        assertTrue(PassthroughOpenRetryPolicy.mayRetryNow(true, 0L))
    }

    @Test
    fun hdmiRoutesAreRecognised() {
        assertTrue(PassthroughOpenRetryPolicy.isHdmiRoute("type:hdmi|name:am9_pro"))
        assertTrue(PassthroughOpenRetryPolicy.isHdmiRoute("type:hdmi_arc|name:tv"))
        assertFalse(PassthroughOpenRetryPolicy.isHdmiRoute("type:built_in_speaker|name:am9_pro"))
        assertFalse(PassthroughOpenRetryPolicy.isHdmiRoute(null))
    }
}
