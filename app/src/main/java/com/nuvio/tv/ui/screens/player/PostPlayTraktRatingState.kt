package com.nuvio.tv.ui.screens.player

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.repository.SkipInterval
import com.nuvio.tv.data.repository.TRAKT_MAX_RATING
import com.nuvio.tv.data.repository.TRAKT_MIN_RATING
import com.nuvio.tv.domain.model.ContentType

internal const val POST_PLAY_TRAKT_RATING_DEFAULT = 6
internal const val POST_PLAY_TRAKT_RATING_SAVED_DISMISS_MS = 1_200L

enum class PostPlayTraktRatingStatus {
    IDLE,
    SAVING,
    SAVED,
    FAILED
}

@Immutable
data class PostPlayTraktRatingUiState(
    val isVisible: Boolean = false,
    val selectedRating: Int = POST_PLAY_TRAKT_RATING_DEFAULT,
    val savedRating: Int? = null,
    val status: PostPlayTraktRatingStatus = PostPlayTraktRatingStatus.IDLE
) {
    val isSelectionSaved: Boolean
        get() = savedRating != null && savedRating == selectedRating
}

internal data class PostPlayTraktRatingTarget(
    val title: String?,
    val year: Int?,
    val ids: TraktIdsDto
)

/** Only movies with at least one Trakt-resolvable id can be rated from the post-play prompt. */
internal fun TrackingMediaReference.toPostPlayTraktRatingTarget(): PostPlayTraktRatingTarget? {
    if (kind != TrackingMediaKind.MOVIE) return null
    val traktIds = TraktIdsDto(
        trakt = ids.trakt?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt(),
        imdb = ids.imdb?.takeIf(String::isNotBlank),
        tmdb = ids.tmdb?.takeIf { it in 1L..Int.MAX_VALUE.toLong() }?.toInt()
    )
    if (traktIds.trakt == null && traktIds.imdb == null && traktIds.tmdb == null) return null
    return PostPlayTraktRatingTarget(title = title, year = year, ids = traktIds)
}

/**
 * The prompt opens at the moment the movie post-play screen would (the credits), or when
 * playback ends, so it also works when post-play recommendations are turned off.
 */
internal fun shouldPromptPostPlayTraktRating(
    contentType: String?,
    playbackEnded: Boolean,
    positionMs: Long,
    durationMs: Long,
    skipIntervals: List<SkipInterval>,
    movieThresholdPercent: Int,
    episodeThresholdMode: NextEpisodeThresholdMode,
    episodeThresholdPercent: Float,
    episodeThresholdMinutesBeforeEnd: Float
): Boolean {
    if (resolvePostPlayContentType(contentType) != ContentType.MOVIE) return false
    if (isShortPlaceholderDuration(durationMs)) return false
    return playbackEnded || shouldShowPostPlayRecommendation(
        contentType = contentType,
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = skipIntervals,
        movieThresholdPercent = movieThresholdPercent,
        episodeThresholdMode = episodeThresholdMode,
        episodeThresholdPercent = episodeThresholdPercent,
        episodeThresholdMinutesBeforeEnd = episodeThresholdMinutesBeforeEnd
    )
}

internal fun stepPostPlayTraktRating(current: Int, delta: Int): Int =
    (current + delta).coerceIn(TRAKT_MIN_RATING, TRAKT_MAX_RATING)

internal fun PostPlayTraktRatingUiState.withSelection(rating: Int): PostPlayTraktRatingUiState {
    if (status == PostPlayTraktRatingStatus.SAVING || rating == selectedRating) return this
    return copy(selectedRating = rating, status = PostPlayTraktRatingStatus.IDLE)
}

internal fun PostPlayTraktRatingUiState.canSubmit(): Boolean =
    isVisible && status != PostPlayTraktRatingStatus.SAVING && !isSelectionSaved
