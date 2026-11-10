package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.segmentCategory

internal fun creditsStartPercent(skipIntervals: List<SkipInterval>, durationMs: Long): Double? {
    if (durationMs <= 0L) return null
    return skipIntervals
        .filter { it.type == "movie-credits" || segmentCategory(it.type) == "ending" }
        .map { it.startTime * 1_000.0 / durationMs * 100.0 }
        .filter { it.isFinite() && it > 0.0 && it < 100.0 }
        .minOrNull()
}

internal fun PlayerRuntimeController.currentCreditsStartPercent(): Double? =
    creditsStartPercent(skipIntervals, currentPlaybackDurationMs())
