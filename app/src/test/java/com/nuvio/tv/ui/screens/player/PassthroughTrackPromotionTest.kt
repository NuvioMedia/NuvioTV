package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PassthroughTrackPromotionTest {

    private val alreadyHandled = RendererCapabilities.create(C.FORMAT_HANDLED)
    private val unsupportedByRenderer = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

    @Test
    fun alreadyHandledTrack_isLeftAlone() {
        assertFalse(
            PassthroughTrackPromotion.shouldPromote(
                alreadyHandled,
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
                policyDenies = false
            )
        )
    }

    @Test
    fun policyDenial_blocksPromotionEvenWhenTheSinkSupports() {
        assertFalse(
            PassthroughTrackPromotion.shouldPromote(
                unsupportedByRenderer,
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
                policyDenies = true
            )
        )
    }

    @Test
    fun sinkUnsupported_blocksPromotionEvenWithoutAPolicyDenial() {
        assertFalse(
            PassthroughTrackPromotion.shouldPromote(
                unsupportedByRenderer,
                AudioSink.SINK_FORMAT_UNSUPPORTED,
                policyDenies = false
            )
        )
    }

    @Test
    fun sinkSupported_andPolicyAllows_promotes() {
        assertTrue(
            PassthroughTrackPromotion.shouldPromote(
                unsupportedByRenderer,
                AudioSink.SINK_FORMAT_SUPPORTED_DIRECTLY,
                policyDenies = false
            )
        )
    }

    @Test
    fun sinkTranscodes_stillPromotesAsDecodable() {
        assertTrue(
            PassthroughTrackPromotion.shouldPromote(
                unsupportedByRenderer,
                AudioSink.SINK_FORMAT_SUPPORTED_WITH_TRANSCODING,
                policyDenies = false
            )
        )
    }
}
