package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddRequestDto
import javax.inject.Inject
import javax.inject.Singleton

sealed interface TraktRatingItem {
    data class Movie(
        val ids: TraktIdsDto
    ) : TraktRatingItem

    data class Episode(
        val showIds: TraktIdsDto,
        val season: Int,
        val number: Int
    ) : TraktRatingItem
}

@Singleton
class TraktRatingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    companion object {
        private const val TAG = "TraktRatingSvc"
    }

    suspend fun getExistingRating(item: TraktRatingItem): Int? {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return null
        if (!traktAuthService.hasRequiredCredentials()) return null

        val type = when (item) {
            is TraktRatingItem.Movie -> "movies"
            is TraktRatingItem.Episode -> "episodes"
        }
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getUserRatings(
                authorization = authHeader,
                id = "me",
                type = type,
                extended = "full"
            )
        } ?: return null
        if (!response.isSuccessful) {
            Log.w(TAG, "getExistingRating failed code=${response.code()} type=$type")
            return null
        }

        return response.body().orEmpty().firstOrNull { rated ->
            when (item) {
                is TraktRatingItem.Movie -> idsMatch(rated.movie?.ids, item.ids)
                is TraktRatingItem.Episode -> {
                    rated.episode?.season == item.season &&
                        rated.episode.number == item.number &&
                        idsMatch(rated.show?.ids, item.showIds)
                }
            }
        }?.rating
    }

    suspend fun submitRating(item: TraktRatingItem, rating: Int): Boolean {
        if (!traktAuthService.getCurrentAuthState().isAuthenticated) return false
        if (!traktAuthService.hasRequiredCredentials()) return false

        val clamped = rating.coerceIn(1, 10)
        val body = when (item) {
            is TraktRatingItem.Movie -> TraktRatingsAddRequestDto(
                movies = listOf(TraktRatedMovieDto(ids = item.ids, rating = clamped))
            )
            is TraktRatingItem.Episode -> {
                val episodeIds = resolveEpisodeIds(item) ?: return false
                TraktRatingsAddRequestDto(
                    episodes = listOf(TraktRatedEpisodeDto(ids = episodeIds, rating = clamped))
                )
            }
        }

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addRatings(authHeader, body)
        } ?: return false
        if (!response.isSuccessful) {
            Log.w(TAG, "submitRating failed code=${response.code()}")
        }
        return response.isSuccessful
    }

    private suspend fun resolveEpisodeIds(item: TraktRatingItem.Episode): TraktIdsDto? {
        val showLookupId = when {
            !item.showIds.imdb.isNullOrBlank() -> item.showIds.imdb
            item.showIds.trakt != null -> item.showIds.trakt.toString()
            !item.showIds.slug.isNullOrBlank() -> item.showIds.slug
            item.showIds.tmdb != null -> "tmdb:${item.showIds.tmdb}"
            item.showIds.tvdb != null -> "tvdb:${item.showIds.tvdb}"
            else -> null
        } ?: return null

        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getShowSeasons(
                authorization = authHeader,
                id = showLookupId,
                extended = "episodes"
            )
        } ?: return null
        if (!response.isSuccessful) {
            Log.w(TAG, "resolveEpisodeIds failed code=${response.code()} id=$showLookupId")
            return null
        }

        return response.body()
            .orEmpty()
            .firstOrNull { it.number == item.season }
            ?.episodes
            .orEmpty()
            .firstOrNull { it.number == item.number }
            ?.ids
            ?.takeIf { it.trakt != null || it.tvdb != null }
    }

    private fun idsMatch(left: TraktIdsDto?, right: TraktIdsDto): Boolean {
        if (left == null) return false
        return (left.trakt != null && left.trakt == right.trakt) ||
            (!left.imdb.isNullOrBlank() && left.imdb == right.imdb) ||
            (left.tmdb != null && left.tmdb == right.tmdb) ||
            (left.tvdb != null && left.tvdb == right.tvdb) ||
            (!left.slug.isNullOrBlank() && left.slug == right.slug)
    }
}
