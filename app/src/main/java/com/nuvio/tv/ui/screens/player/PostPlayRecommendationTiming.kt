package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.ContentType

private const val MOVIE_RECOMMENDATION_PREFETCH_LEAD_PERCENT = 5

internal fun postPlayRecommendationPrefetchProgress(
    contentType: String?,
    movieThresholdPercent: Int
): Float {
    if (resolvePostPlayContentType(contentType) != ContentType.MOVIE) {
        return POST_PLAY_RECOMMENDATION_PREFETCH_PROGRESS
    }
    val threshold = movieThresholdPercent.coerceIn(
        PlayerSettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
        PlayerSettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT
    )
    return (threshold - MOVIE_RECOMMENDATION_PREFETCH_LEAD_PERCENT) / 100f
}

internal fun shouldShowPostPlayRecommendation(
    contentType: String?,
    positionMs: Long,
    durationMs: Long,
    skipIntervals: List<SkipInterval>,
    movieThresholdPercent: Int,
    episodeThresholdMode: NextEpisodeThresholdMode,
    episodeThresholdPercent: Float,
    episodeThresholdMinutesBeforeEnd: Float
): Boolean {
    return when (resolvePostPlayContentType(contentType)) {
        ContentType.MOVIE -> shouldShowMovieRecommendation(
            positionMs = positionMs,
            durationMs = durationMs,
            thresholdPercent = movieThresholdPercent
        )
        ContentType.SERIES -> PlayerNextEpisodeRules.shouldShowNextEpisodeCard(
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = skipIntervals,
            thresholdMode = episodeThresholdMode,
            thresholdPercent = episodeThresholdPercent,
            thresholdMinutesBeforeEnd = episodeThresholdMinutesBeforeEnd
        )
        else -> false
    }
}

private fun shouldShowMovieRecommendation(
    positionMs: Long,
    durationMs: Long,
    thresholdPercent: Int
): Boolean {
    if (durationMs <= 0L) return false
    val threshold = thresholdPercent.coerceIn(
        PlayerSettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
        PlayerSettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT
    )
    val position = positionMs.coerceIn(0L, durationMs)
    return position.toDouble() / durationMs.toDouble() >= threshold / 100.0
}
