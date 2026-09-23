package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.domain.model.ContentType

private const val MOVIE_RECOMMENDATION_PREFETCH_LEAD_PERCENT = 5

internal fun postPlayRecommendationPrefetchProgress(
    contentType: String?,
    movieThresholdPercent: Int,
    durationMs: Long = 0L,
    skipIntervals: List<SkipInterval> = emptyList(),
    episodeThresholdMode: NextEpisodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
    episodeThresholdPercent: Float = 99f,
    episodeThresholdMinutesBeforeEnd: Float = 2f
): Float {
    if (resolvePostPlayContentType(contentType) != ContentType.MOVIE) {
        return POST_PLAY_RECOMMENDATION_PREFETCH_PROGRESS
    }
    val threshold = movieThresholdPercent.coerceIn(
        PlayerSettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
        PlayerSettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT
    )
    val triggerProgress = if (durationMs > 0L) {
        movieRecommendationTriggerPositionMs(
            durationMs, threshold, skipIntervals, episodeThresholdMode,
            episodeThresholdPercent, episodeThresholdMinutesBeforeEnd
        ).toFloat() / durationMs
    } else {
        threshold / 100f
    }
    return (triggerProgress - MOVIE_RECOMMENDATION_PREFETCH_LEAD_PERCENT / 100f).coerceAtLeast(0f)
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
            thresholdPercent = movieThresholdPercent,
            skipIntervals = skipIntervals,
            episodeThresholdMode = episodeThresholdMode,
            episodeThresholdPercent = episodeThresholdPercent,
            episodeThresholdMinutesBeforeEnd = episodeThresholdMinutesBeforeEnd
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
    thresholdPercent: Int,
    skipIntervals: List<SkipInterval>,
    episodeThresholdMode: NextEpisodeThresholdMode,
    episodeThresholdPercent: Float,
    episodeThresholdMinutesBeforeEnd: Float
): Boolean {
    if (durationMs <= 0L) return false
    val position = positionMs.coerceIn(0L, durationMs)
    return position >= movieRecommendationTriggerPositionMs(
        durationMs, thresholdPercent, skipIntervals, episodeThresholdMode,
        episodeThresholdPercent, episodeThresholdMinutesBeforeEnd
    )
}

private fun movieRecommendationTriggerPositionMs(
    durationMs: Long,
    thresholdPercent: Int,
    skipIntervals: List<SkipInterval>,
    episodeThresholdMode: NextEpisodeThresholdMode,
    episodeThresholdPercent: Float,
    episodeThresholdMinutesBeforeEnd: Float
): Long {
    val threshold = thresholdPercent.coerceIn(
        PlayerSettings.MIN_POST_PLAY_MOVIE_THRESHOLD_PERCENT,
        PlayerSettings.MAX_POST_PLAY_MOVIE_THRESHOLD_PERCENT
    )
    val fallbackPositionMs = kotlin.math.ceil(durationMs * (threshold / 100.0)).toLong()
    val validIntervals = skipIntervals.filter {
        it.startTime.isFinite() && it.endTime.isFinite() &&
            it.startTime >= 0.0 && it.endTime > it.startTime &&
            it.startTime * 1_000.0 < durationMs
    }
    val credits = validIntervals.filter {
        it.type == "movie-credits" &&
            it.endTime * 1_000.0 <= durationMs + PlayerNextEpisodeRules.END_OF_VIDEO_EPSILON_MS
    }
    val firstCreditsStart = credits.minOfOrNull { it.startTime }
    val scenes = validIntervals.filter {
        it.type == "post-credits" && (firstCreditsStart == null || it.startTime >= firstCreditsStart)
    }
    if (scenes.isNotEmpty()) {
        // Keep a playable scene even when its submitted end exceeds this release's runtime.
        return (scenes.maxOf { it.endTime } * 1_000.0).toLong().coerceAtMost(durationMs)
    }
    if (credits.isEmpty()) return fallbackPositionMs

    val latestCreditsEndMs = (credits.maxOf { it.endTime } * 1_000.0).toLong()
    val postCreditsGapMs = durationMs - latestCreditsEndMs
    val userThresholdMs = when (episodeThresholdMode) {
        NextEpisodeThresholdMode.PERCENTAGE ->
            ((1.0 - episodeThresholdPercent.coerceIn(97f, 100f) / 100.0) * durationMs).toLong()
        NextEpisodeThresholdMode.MINUTES_BEFORE_END ->
            (episodeThresholdMinutesBeforeEnd.coerceIn(0f, 3.5f) * 60_000f).toLong()
    }
    // An unexplained tail may contain a scene: use the later episode threshold, not the movie setting.
    return if (postCreditsGapMs > userThresholdMs) {
        (durationMs - userThresholdMs).coerceAtLeast(latestCreditsEndMs)
    } else {
        (credits.minOf { it.startTime } * 1_000.0).toLong()
    }
}
