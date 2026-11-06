package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingMovieRequestItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddResponseDto
import javax.inject.Inject
import javax.inject.Singleton

internal const val TRAKT_MIN_RATING = 1
internal const val TRAKT_MAX_RATING = 10

@Singleton
class TraktRatingService @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService
) {
    companion object {
        private const val TAG = "TraktRatingSvc"
    }

    /** Returns the user's existing Trakt rating (1-10) for the movie, or null if none or unavailable. */
    suspend fun getMovieRating(ids: TraktIdsDto): Int? {
        if (!canUseTrakt() || !ids.hasAnyId()) return null
        val response = traktAuthService.executeAuthorizedRequest { authHeader ->
            traktApi.getMovieRatings(authHeader)
        } ?: return null
        if (!response.isSuccessful) {
            Log.w(TAG, "Fetching movie ratings failed with code ${response.code()}")
            return null
        }
        return findMovieRating(response.body().orEmpty(), ids)
    }

    /** Saves [rating] (1-10) for the movie on Trakt. Returns true when Trakt accepted it. */
    suspend fun rateMovie(
        title: String?,
        year: Int?,
        ids: TraktIdsDto,
        rating: Int
    ): Boolean {
        if (rating !in TRAKT_MIN_RATING..TRAKT_MAX_RATING) return false
        if (!canUseTrakt() || !ids.hasAnyId()) return false
        val body = TraktRatingsAddRequestDto(
            movies = listOf(
                TraktRatingMovieRequestItemDto(
                    rating = rating,
                    title = title,
                    year = year,
                    ids = ids
                )
            )
        )
        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
            traktApi.addRatings(authHeader, body)
        } ?: return false
        if (!response.isSuccessful) {
            Log.w(TAG, "Rating movie failed with code ${response.code()}")
            return false
        }
        return isMovieRatingAccepted(response.body())
    }

    private suspend fun canUseTrakt(): Boolean {
        return traktAuthService.hasRequiredCredentials() &&
            traktAuthService.getCurrentAuthState().isAuthenticated
    }
}

internal fun findMovieRating(items: List<TraktRatingItemDto>, ids: TraktIdsDto): Int? {
    return items.firstOrNull { item ->
        val movieIds = item.movie?.ids ?: return@firstOrNull false
        (ids.trakt != null && ids.trakt == movieIds.trakt) ||
            (!ids.imdb.isNullOrBlank() && ids.imdb.equals(movieIds.imdb, ignoreCase = true)) ||
            (ids.tmdb != null && ids.tmdb == movieIds.tmdb)
    }?.rating?.takeIf { it in TRAKT_MIN_RATING..TRAKT_MAX_RATING }
}

internal fun isMovieRatingAccepted(body: TraktRatingsAddResponseDto?): Boolean {
    if (body == null) return false
    if (!body.notFound?.movies.isNullOrEmpty()) return false
    return (body.added?.movies ?: 0) > 0
}
