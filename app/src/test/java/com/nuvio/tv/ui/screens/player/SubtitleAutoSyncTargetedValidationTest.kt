package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncTargetedValidationTest {
    @Test
    fun `last borderline local candidate starts one validation round`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(offsetMs = 49_500),
                isLastStandardProbe = true
            )
        )
    }

    @Test
    fun `weak fifty percent candidates do not start validation before the final probe`() {
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(offsetMs = 49_500, margin = 0.01),
                isLastStandardProbe = false
            )
        )
    }

    @Test
    fun `strong intermediate local peak cannot consume final validation`() {
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(
                    offsetMs = 49_700,
                    confidence = 0.5008,
                    margin = 0.0307,
                    sigma = 1.65,
                    agreement = 1.0 / 6.0,
                    windows = 6
                ),
                isLastStandardProbe = false
            )
        )
    }

    @Test
    fun `final cumulative result can nominate a consistent borderline peak`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(
                    offsetMs = 49_900,
                    confidence = 0.5244,
                    margin = 0.0167,
                    sigma = 1.285,
                    agreement = 0.50,
                    windows = 4
                ),
                isLastStandardProbe = true
            )
        )
    }

    @Test
    fun `final local candidate nomination relies on structural evidence not score thresholds`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(
                    offsetMs = 49_600,
                    confidence = 0.20,
                    margin = 0.0,
                    sigma = 0.0,
                    agreement = 0.0,
                    windows = 4
                ),
                isLastStandardProbe = true
            )
        )
    }

    @Test
    fun `final candidate still needs a local offset and four evidence windows`() {
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(
                    offsetMs = 49_600,
                    confidence = 0.20,
                    margin = 0.0,
                    sigma = 0.0,
                    agreement = 0.0,
                    windows = 3
                ),
                isLastStandardProbe = true
            )
        )
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(offsetMs = 241_000, windows = 6),
                isLastStandardProbe = true
            )
        )
    }

    @Test
    fun `same weaker cumulative result cannot nominate before the final probe`() {
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStart(
                candidate = result(
                    offsetMs = 49_900,
                    confidence = 0.5244,
                    margin = 0.0167,
                    sigma = 1.285,
                    agreement = 0.50,
                    windows = 4
                ),
                isLastStandardProbe = false
            )
        )
    }

    @Test
    fun `complete final probe can nominate a borderline local candidate`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.shouldStartFromFinalIndependentProbe(
                result(
                    offsetMs = 49_500,
                    confidence = 0.50,
                    margin = 0.025,
                    sigma = 1.4,
                    agreement = 0.34,
                    windows = 3
                )
            )
        )
    }

    @Test
    fun `final independent probe cannot nominate a weak or distant peak`() {
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStartFromFinalIndependentProbe(
                result(offsetMs = 49_500, confidence = 0.47)
            )
        )
        assertFalse(
            SubtitleAutoSyncTargetedValidation.shouldStartFromFinalIndependentProbe(
                result(offsetMs = 241_000)
            )
        )
    }

    @Test
    fun `two independent narrow results confirm the candidate`() {
        val candidate = result(offsetMs = 49_500)
        val confirmed = SubtitleAutoSyncTargetedValidation.confirmedResult(
            candidate = candidate,
            independentResults = listOf(
                result(offsetMs = 49_300, confidence = 0.58, margin = 0.04, sigma = 1.8),
                result(offsetMs = 49_700, confidence = 0.61, margin = 0.05, sigma = 2.0)
            )
        )

        assertNotNull(confirmed)
        requireNotNull(confirmed)
        assertEquals(49_500, confirmed.offsetMs)
        assertTrue(confirmed.shouldApply)
        assertEquals(1.0, confirmed.windowAgreement, 0.0)
    }

    @Test
    fun `narrow confirmation relies on offset evidence and sigma not unstable score gates`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.confirms(
                candidateOffsetMs = 57_600,
                result = result(
                    offsetMs = 57_600,
                    confidence = 0.485,
                    margin = 0.006,
                    sigma = 1.97,
                    agreement = 0.50,
                    windows = 2
                )
            )
        )
    }

    @Test
    fun `confirmation allows the observed eleven hundred millisecond variation`() {
        assertTrue(
            SubtitleAutoSyncTargetedValidation.confirms(
                candidateOffsetMs = 49_500,
                result = result(offsetMs = 50_600, sigma = 3.23, windows = 2)
            )
        )
    }

    @Test
    fun `one confirmation or a distant result cannot apply`() {
        val candidate = result(offsetMs = 49_500)
        assertNull(
            SubtitleAutoSyncTargetedValidation.confirmedResult(
                candidate,
                listOf(result(offsetMs = 49_400))
            )
        )
        assertNull(
            SubtitleAutoSyncTargetedValidation.confirmedResult(
                candidate,
                listOf(result(offsetMs = 49_400), result(offsetMs = 52_600))
            )
        )
    }

    @Test
    fun `validation planner picks two dense windows outside existing evidence`() {
        val cues = buildList {
            listOf(120_000L, 600_000L, 1_200_000L).forEachIndexed { group, base ->
                repeat(12) { index ->
                    val start = base + index * (2_000L + group * 100L)
                    add(SubtitleSyncCue(start, start + 1_000L, "Dialogue $group-$index"))
                }
            }
        }
        val positions = planSubtitleAutoSyncValidationPositions(
            cues = cues,
            candidateOffsetMs = 50_000,
            durationMs = 2_000_000L,
            excludedObservedSpans = listOf(SubtitleSyncSpan(160_000L, 240_000L))
        )

        assertEquals(2, positions.size)
        assertTrue(positions.all { it > 500_000L })
        assertTrue(kotlin.math.abs(positions[0] - positions[1]) >= 90_000L)
    }

    private fun result(
        offsetMs: Int,
        confidence: Double = 0.562,
        margin: Double = 0.036,
        sigma: Double = 1.68,
        agreement: Double = 0.25,
        windows: Int = 4
    ) = SubtitleAutoSyncResult(
        offsetMs = offsetMs,
        confidence = confidence,
        scoreMargin = margin,
        sigma = sigma,
        windowAgreement = agreement,
        evidenceWindows = windows,
        rejection = SubtitleAutoSyncRejection.LOW_CONFIDENCE
    )
}
