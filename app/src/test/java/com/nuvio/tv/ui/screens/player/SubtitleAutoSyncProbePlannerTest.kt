package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SubtitleAutoSyncProbePlannerTest {
    @Test
    fun `known duration produces separated windows across timeline`() {
        val positions = planSubtitleAutoSyncProbePositions(
            currentPositionMs = 3_500_000L,
            durationMs = 7_200_000L,
            maxAttempts = 3
        )

        assertEquals(3, positions.size)
        assertEquals(3_500_000L, positions.first())
        assertTrue(positions.zipWithNext().all { (left, right) ->
            kotlin.math.abs(left - right) >= 75_000L
        })
    }

    @Test
    fun `unknown duration near eof retries progressively earlier`() {
        val positions = planSubtitleAutoSyncProbePositions(
            currentPositionMs = 1_000_000L,
            durationMs = 0L,
            maxAttempts = 3
        )

        assertEquals(listOf(1_000_000L, 910_000L, 820_000L), positions)
    }

    @Test
    fun `short media does not repeat the same effective window`() {
        val positions = planSubtitleAutoSyncProbePositions(
            currentPositionMs = 70_000L,
            durationMs = 80_000L,
            maxAttempts = 3
        )

        assertEquals(1, positions.size)
    }
}
