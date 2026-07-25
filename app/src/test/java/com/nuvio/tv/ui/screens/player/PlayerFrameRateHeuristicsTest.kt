package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.player.FrameRateUtils
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Tests for cinema 24 / 23.976 ambiguity handling used when track metadata
 * reports a coarse 24.0 fps and the preflight probe can refine to NTSC film.
 */
class PlayerFrameRateHeuristicsTest {

    private val ntscFilm = 24000f / 1001f

    @Test
    fun `isAmbiguousCinema24 covers 24_00 band only`() {
        assertTrue(PlayerFrameRateHeuristics.isAmbiguousCinema24(24.0f))
        assertTrue(PlayerFrameRateHeuristics.isAmbiguousCinema24(23.95f))
        assertTrue(PlayerFrameRateHeuristics.isAmbiguousCinema24(24.05f))
        assertTrue(
            "23.976 sits inside the ambiguous track window (23.95..24.05)",
            PlayerFrameRateHeuristics.isAmbiguousCinema24(23.976f)
        )
        assertFalse(PlayerFrameRateHeuristics.isAmbiguousCinema24(25f))
        assertFalse(PlayerFrameRateHeuristics.isAmbiguousCinema24(30f))
        assertFalse(PlayerFrameRateHeuristics.isAmbiguousCinema24(23.90f))
        assertFalse(PlayerFrameRateHeuristics.isAmbiguousCinema24(24.10f))
    }

    @Test
    fun `shouldProbeOverrideTrack rejects non-TRACK sources`() {
        val state = baseState(
            source = FrameRateSource.PROBE,
            raw = 24f,
            snapped = 24f
        )
        val detection = FrameRateUtils.FrameRateDetection(raw = ntscFilm, snapped = ntscFilm)
        assertFalse(PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection))
    }

    @Test
    fun `shouldProbeOverrideTrack rejects unambiguous track rates`() {
        val state = baseState(
            source = FrameRateSource.TRACK,
            raw = 25f,
            snapped = 25f
        )
        val detection = FrameRateUtils.FrameRateDetection(raw = ntscFilm, snapped = ntscFilm)
        assertFalse(PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection))
    }

    @Test
    fun `shouldProbeOverrideTrack accepts NTSC film probe refining ambiguous 24 track`() {
        val state = baseState(
            source = FrameRateSource.TRACK,
            raw = 24.0f,
            snapped = 24.0f
        )
        val detection = FrameRateUtils.FrameRateDetection(
            raw = 23.976f,
            snapped = ntscFilm
        )
        assertTrue(
            "Probe must be allowed to correct ambiguous 24.0 track to 23.976",
            PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection)
        )
        assertTrue(abs(detection.snapped - ntscFilm) < 0.01f)
    }

    @Test
    fun `shouldProbeOverrideTrack rejects probe that is not NTSC film`() {
        val state = baseState(
            source = FrameRateSource.TRACK,
            raw = 24.0f,
            snapped = 24.0f
        )
        // Probe snaps to true 24 — not a correction to 23.976
        val detection = FrameRateUtils.FrameRateDetection(raw = 24.0f, snapped = 24.0f)
        assertFalse(PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection))
    }

    @Test
    fun `shouldProbeOverrideTrack rejects when snapped already matches closely`() {
        val state = baseState(
            source = FrameRateSource.TRACK,
            raw = 23.976f,
            snapped = ntscFilm
        )
        val detection = FrameRateUtils.FrameRateDetection(raw = 23.976f, snapped = ntscFilm)
        // differsEnough requires |probe.snapped - state.detectedFrameRate| > 0.015
        assertFalse(PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection))
    }

    @Test
    fun `shouldProbeOverrideTrack uses detectedFrameRate when raw is zero`() {
        val state = PlayerUiState(
            detectedFrameRateSource = FrameRateSource.TRACK,
            detectedFrameRateRaw = 0f,
            detectedFrameRate = 24.0f
        )
        val detection = FrameRateUtils.FrameRateDetection(raw = 23.976f, snapped = ntscFilm)
        assertTrue(PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection))
    }

    private fun baseState(
        source: FrameRateSource?,
        raw: Float,
        snapped: Float
    ) = PlayerUiState(
        detectedFrameRateSource = source,
        detectedFrameRateRaw = raw,
        detectedFrameRate = snapped
    )
}
