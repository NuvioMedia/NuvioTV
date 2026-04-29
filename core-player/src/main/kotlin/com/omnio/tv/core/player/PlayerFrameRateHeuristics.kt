package com.omnio.tv.core.player

import com.omnio.tv.core.player.FrameRateUtils
import kotlin.math.abs

internal object PlayerFrameRateHeuristics {
    private const val AMBIGUOUS_CINEMA_TRACK_MIN = 23.95f
    private const val AMBIGUOUS_CINEMA_TRACK_MAX = 24.05f
    private const val FRAME_RATE_CORRECTION_EPSILON = 0.015f
    private const val NTSC_FILM_FPS = 24000f / 1001f

    fun isAmbiguousCinema24(value: Float): Boolean {
        return value in AMBIGUOUS_CINEMA_TRACK_MIN..AMBIGUOUS_CINEMA_TRACK_MAX
    }

    fun shouldProbeOverrideTrack(
        state: PlayerUiState,
        detection: FrameRateUtils.FrameRateDetection
    ): Boolean {
        if (state.detectedFrameRateSource != FrameRateSource.TRACK) return false

        val trackRaw = if (state.detectedFrameRateRaw > 0f) {
            state.detectedFrameRateRaw
        } else {
            state.detectedFrameRate
        }
        val trackIsAmbiguous = isAmbiguousCinema24(trackRaw) || isAmbiguousCinema24(state.detectedFrameRate)
        if (!trackIsAmbiguous) return false

        val probeIsNtscFilm = abs(detection.snapped - NTSC_FILM_FPS) < 0.01f
        val differsEnough = abs(detection.snapped - state.detectedFrameRate) > FRAME_RATE_CORRECTION_EPSILON
        return probeIsNtscFilm && differsEnough
    }
}
