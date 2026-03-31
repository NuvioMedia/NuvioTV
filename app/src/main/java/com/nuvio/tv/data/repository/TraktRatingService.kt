package com.nuvio.tv.data.repository

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedEpisodeItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedMovieItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingEpisodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddRequestDto
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktRatingItem {
    data class Movie(
        val ids: TraktIdsDto,
        val title: String? = null
    ) : TraktRatingItem

    data class Episode(
        val ids: TraktIdsDto,
        val showTitle: String? = null,
        val season: Int,
        val number: Int,
        val episodeTitle: String? = null
    ) : TraktRatingItem
}

@Singleton
class TraktRatingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val profileManager: ProfileManager,
    private val traktSettingsDataStore: TraktSettingsDataStore
) {
    suspend fun canPromptForRating(item: TraktRatingItem?): Boolean {
        if (item == null) return false
        if (profileManager.activeProfileId.value != 1) return false
        if (!traktAuthService.hasRequiredCredentials()) return false
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        val promptsEnabledForItem = when (item) {
            is TraktRatingItem.Movie -> traktSettingsDataStore.rateMoviesAfterWatching.first()
            is TraktRatingItem.Episode -> traktSettingsDataStore.rateEpisodesAfterWatching.first()
        }
        if (!promptsEnabledForItem) return false
        return when (item) {
            is TraktRatingItem.Movie -> item.ids.hasAnyId()
            is TraktRatingItem.Episode -> item.ids.trakt != null || item.ids.tvdb != null
        }
    }

    suspend fun getDefaultRating(): Int {
        return traktSettingsDataStore.defaultRatingPromptValue.first()
            .coerceIn(
                minimumValue = TraktSettingsDataStore.MIN_RATING_PROMPT_VALUE,
                maximumValue = TraktSettingsDataStore.MAX_RATING_PROMPT_VALUE
            )
    }

    suspend fun getExistingRating(item: TraktRatingItem): Int? {
        if (!canPromptForRating(item)) return null

        return when (item) {
            is TraktRatingItem.Movie -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getRatedMovies(authorization = authHeader)
                } ?: return null

                if (!response.isSuccessful) return null
                findMovieRating(item.ids, response.body().orEmpty())
            }

            is TraktRatingItem.Episode -> {
                val response = traktAuthService.executeAuthorizedRequest { authHeader ->
                    traktApi.getRatedEpisodes(authorization = authHeader)
                } ?: return null

                if (!response.isSuccessful) return null
                findEpisodeRating(item.ids, response.body().orEmpty())
            }
        }
    }

    suspend fun submitRating(item: TraktRatingItem, rating: Int): Result<Unit> {
        val normalizedRating = rating.coerceIn(1, 10)
        if (!canPromptForRating(item)) {
            return Result.failure(IllegalStateException("Trakt rating is unavailable"))
        }

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addRatings(
                authorization = authHeader,
                body = buildRequestBody(item, normalizedRating)
            )
        } ?: return Result.failure(IllegalStateException("Unable to reach Trakt"))

        return if (response.isSuccessful) {
            Result.success(Unit)
        } else {
            Result.failure(
                IllegalStateException("Failed to save Trakt rating (${response.code()})")
            )
        }
    }

    internal fun buildRequestBody(item: TraktRatingItem, rating: Int): TraktRatingsAddRequestDto {
        val normalizedRating = rating.coerceIn(1, 10)
        return when (item) {
            is TraktRatingItem.Movie -> TraktRatingsAddRequestDto(
                movies = listOf(
                    TraktRatingMovieRequestDto(
                        rating = normalizedRating,
                        ids = item.ids
                    )
                )
            )

            is TraktRatingItem.Episode -> TraktRatingsAddRequestDto(
                episodes = listOf(
                    TraktRatingEpisodeRequestDto(
                        rating = normalizedRating,
                        ids = item.ids
                    )
                )
            )
        }
    }

    internal fun findMovieRating(
        targetIds: TraktIdsDto,
        ratings: List<TraktRatedMovieItemDto>
    ): Int? {
        return ratings.firstNotNullOfOrNull { ratedItem ->
            val rating = ratedItem.rating?.coerceIn(1, 10) ?: return@firstNotNullOfOrNull null
            if (idsMatch(targetIds, ratedItem.movie?.ids)) rating else null
        }
    }

    internal fun findEpisodeRating(
        targetIds: TraktIdsDto,
        ratings: List<TraktRatedEpisodeItemDto>
    ): Int? {
        return ratings.firstNotNullOfOrNull { ratedItem ->
            val rating = ratedItem.rating?.coerceIn(1, 10) ?: return@firstNotNullOfOrNull null
            if (idsMatch(targetIds, ratedItem.episode?.ids)) rating else null
        }
    }

    private fun idsMatch(target: TraktIdsDto, candidate: TraktIdsDto?): Boolean {
        candidate ?: return false
        return listOf(
            target.trakt != null && target.trakt == candidate.trakt,
            !target.imdb.isNullOrBlank() && target.imdb == candidate.imdb,
            target.tmdb != null && target.tmdb == candidate.tmdb,
            target.tvdb != null && target.tvdb == candidate.tvdb,
            !target.slug.isNullOrBlank() && target.slug == candidate.slug
        ).any { it }
    }
}
