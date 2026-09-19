package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleFastAudioProbeProgressTest {
    @Test
    fun `absolute timestamp beyond target does not count as decoded duration`() {
        val spans = listOf(
            SubtitleSyncSpan(
                startMs = 3_500_000L,
                endMs = 3_500_031L
            )
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `sixty seconds of observed PCM reaches target regardless of absolute position`() {
        val spans = listOf(
            SubtitleSyncSpan(
                startMs = 3_500_000L,
                endMs = 3_560_000L
            )
        )

        assertTrue(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans
            )
        )
    }

    @Test
    fun `gaps between observed spans are not counted as decoded PCM`() {
        val spans = listOf(
            SubtitleSyncSpan(startMs = 1_000_000L, endMs = 1_020_000L),
            SubtitleSyncSpan(startMs = 1_120_000L, endMs = 1_140_000L)
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }

    @Test
    fun `overlapping spans are not counted twice`() {
        val spans = listOf(
            SubtitleSyncSpan(startMs = 2_000_000L, endMs = 2_040_000L),
            SubtitleSyncSpan(startMs = 2_020_000L, endMs = 2_050_000L)
        )

        assertFalse(
            hasReachedSubtitleFastAudioTarget(
                observedSpans = spans,
                targetDurationMs = 60_000L
            )
        )
    }
}
