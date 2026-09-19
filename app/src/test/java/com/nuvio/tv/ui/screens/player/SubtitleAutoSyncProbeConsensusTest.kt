package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncProbeConsensusTest {
    @Test
    fun `promotes three stable high quality probe results`() {
        val consensus = SubtitleAutoSyncProbeConsensus.stableResult(
            listOf(
                result(offsetMs = -1_356, confidence = 0.761, agreement = 0.667),
                result(offsetMs = -1_356, confidence = 0.749, agreement = 0.500),
                result(offsetMs = -1_556, confidence = 0.757, agreement = 0.500)
            )
        )

        requireNotNull(consensus)
        assertEquals(-1_356, consensus.offsetMs)
        assertTrue(consensus.shouldApply)
    }

    @Test
    fun `does not promote only two matching probes`() {
        val consensus = SubtitleAutoSyncProbeConsensus.stableResult(
            listOf(
                result(offsetMs = -1_400),
                result(offsetMs = -1_600)
            )
        )

        assertNull(consensus)
    }

    @Test
    fun `rejects probe offsets that jump between unrelated scenes`() {
        val consensus = SubtitleAutoSyncProbeConsensus.stableResult(
            listOf(
                result(offsetMs = 729_343),
                result(offsetMs = -442_057),
                result(offsetMs = 965_943),
                result(offsetMs = -757_239)
            )
        )

        assertNull(consensus)
    }

    @Test
    fun `does not promote stable offsets with weak peaks`() {
        val consensus = SubtitleAutoSyncProbeConsensus.stableResult(
            listOf(
                result(offsetMs = -1_400, confidence = 0.71),
                result(offsetMs = -1_500, margin = 0.02),
                result(offsetMs = -1_600, agreement = 0.40)
            )
        )

        assertNull(consensus)
    }

    private fun result(
        offsetMs: Int,
        confidence: Double = 0.76,
        margin: Double = 0.10,
        agreement: Double = 0.65
    ) = SubtitleAutoSyncResult(
        offsetMs = offsetMs,
        confidence = confidence,
        scoreMargin = margin,
        sigma = 3.0,
        windowAgreement = agreement,
        evidenceWindows = 6,
        rejection = SubtitleAutoSyncRejection.LOW_CONFIDENCE
    )
}
