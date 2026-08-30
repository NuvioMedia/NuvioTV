package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MDBListRatings
import com.nuvio.tv.domain.model.MetaPreview
import kotlin.math.ceil

@Immutable
data class PostPlayRecommendation(
    val id: String,
    val contentType: String,
    val title: String,
    val poster: String?,
    val backdrop: String?,
    val logo: String?,
    val description: String?,
    val releaseInfo: String?,
    val rating: Float?,
    val genres: List<String>,
    val runtime: String?,
    val sourceAddonBaseUrl: String? = null,
    val tmdbId: String? = null,
    val tmdbRating: Float? = null,
    val ageRating: String? = null,
    val status: String? = null,
    val country: String? = null,
    val language: String? = null,
    val contentLanguage: String? = null,
    val mdbListRatings: MDBListRatings? = null,
    val showStandardRatings: Boolean = true,
    val trailerVideoUrl: String? = null,
    val trailerAudioUrl: String? = null
) {
    val hasTrailer: Boolean
        get() = !trailerVideoUrl.isNullOrBlank()
}

@Immutable
data class PostPlayRecommendationUiState(
    val recommendation: PostPlayRecommendation? = null,
    val recommendationIndex: Int = 0,
    val recommendationCount: Int = 0,
    val isLoadingRecommendation: Boolean = false,
    val isChangingRecommendation: Boolean = false,
    val isLoadingTrailer: Boolean = false,
    val isVisible: Boolean = false,
    val hasReturnedToPlayer: Boolean = false,
    val countdownSeconds: Int? = null,
    val isTrailerPlaying: Boolean = false,
    val hasAutoPlayedTrailer: Boolean = false
) {
    val canNavigatePrevious: Boolean
        get() = !isChangingRecommendation && recommendationIndex > 0

    val canNavigateNext: Boolean
        get() = !isChangingRecommendation && recommendationIndex < recommendationCount - 1

    val canReturnToPlayer: Boolean
        get() = isVisible && !isTrailerPlaying && !hasAutoPlayedTrailer

    val blocksNaturalCompletion: Boolean
        get() = recommendation != null || isVisible || hasReturnedToPlayer || isLoadingRecommendation
}

internal fun PostPlayRecommendationUiState.returnToPlayer(): PostPlayRecommendationUiState {
    if (!canReturnToPlayer) return this
    return copy(
        isVisible = false,
        hasReturnedToPlayer = true,
        countdownSeconds = null
    )
}

internal fun PlayerUiState.blocksPostPlayRecommendation(): Boolean {
    return pendingPreviewSeekPosition != null ||
        showPauseOverlay ||
        showStreamInfoOverlay ||
        showEpisodesPanel ||
        showSourcesPanel ||
        showAudioOverlay ||
        showSubtitleOverlay ||
        showSubtitleStylePanel ||
        showSubtitleDelayOverlay ||
        showSubtitleTimingDialog ||
        showSpeedDialog ||
        showMoreDialog
}

internal const val POST_PLAY_RECOMMENDATION_PREFETCH_PROGRESS = 0.9f
internal const val POST_PLAY_RECOMMENDATION_PREFETCH_REMAINING_MS = 10 * 60_000L
internal const val POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS = 5
internal const val POST_PLAY_RECOMMENDATION_TRANSITION_MS = 420

internal fun shouldPrefetchPostPlayRecommendation(
    positionMs: Long,
    durationMs: Long,
    progressThreshold: Float = POST_PLAY_RECOMMENDATION_PREFETCH_PROGRESS
): Boolean {
    if (durationMs <= 0L) return false
    val position = positionMs.coerceIn(0L, durationMs)
    val remaining = durationMs - position
    val progress = position.toDouble() / durationMs.toDouble()
    return progress >= progressThreshold.coerceIn(0f, 1f) ||
        remaining <= POST_PLAY_RECOMMENDATION_PREFETCH_REMAINING_MS
}

internal fun postPlayRecommendationCountdownSeconds(
    positionMs: Long,
    durationMs: Long
): Int? {
    if (durationMs <= 0L) return null
    val remainingMs = (durationMs - positionMs).coerceAtLeast(0L)
    if (remainingMs > POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS * 1_000L) return null
    return ceil(remainingMs / 1_000.0)
        .toInt()
        .coerceIn(1, POST_PLAY_RECOMMENDATION_TRAILER_COUNTDOWN_SECONDS)
}

internal fun shouldUsePostPlayRecommendation(
    contentType: String?,
    isNextEpisodeMetadataResolved: Boolean,
    nextEpisodeHasAired: Boolean?,
    enabled: Boolean = true
): Boolean = enabled && when (resolvePostPlayContentType(contentType)) {
    ContentType.MOVIE -> true
    ContentType.SERIES -> isNextEpisodeMetadataResolved && nextEpisodeHasAired != true
    else -> false
}

internal fun resolvePostPlayContentType(
    apiType: String?,
    fallback: ContentType? = null
): ContentType? {
    return when (apiType?.trim()?.lowercase()) {
        "movie", "film" -> ContentType.MOVIE
        "series", "tv", "show", "tvshow" -> ContentType.SERIES
        else -> when (fallback) {
            ContentType.MOVIE -> ContentType.MOVIE
            ContentType.SERIES, ContentType.TV -> ContentType.SERIES
            else -> null
        }
    }
}

internal fun isPostPlayCandidateWatched(
    candidate: MetaPreview,
    watchedMovieIds: Set<String>,
    watchedSeriesIds: Set<String>
): Boolean {
    val watchedIds = when (resolvePostPlayContentType(candidate.apiType, candidate.type)) {
        ContentType.MOVIE -> watchedMovieIds
        ContentType.SERIES -> watchedSeriesIds
        else -> return false
    }
    return candidate.id in watchedIds || candidate.imdbId?.let(watchedIds::contains) == true
}

internal fun shouldShowPostPlayTrailerAction(
    recommendation: PostPlayRecommendation,
    isTrailerPlaying: Boolean,
    inAppTrailerPlaybackEnabled: Boolean
): Boolean = inAppTrailerPlaybackEnabled && recommendation.hasTrailer && !isTrailerPlaying
