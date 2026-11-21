package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListMutationCountDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListMutationNotFoundDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatingsAddResponseDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktRatingServiceTest {

    private val ratings = listOf(
        TraktRatingItemDto(rating = 9, movie = TraktMovieDto(ids = TraktIdsDto(trakt = 1, imdb = "tt0000001", tmdb = 11))),
        TraktRatingItemDto(rating = 4, movie = TraktMovieDto(ids = TraktIdsDto(trakt = 2, imdb = "tt0000002", tmdb = 22)))
    )

    @Test
    fun `findMovieRating matches by trakt imdb or tmdb id`() {
        assertEquals(9, findMovieRating(ratings, TraktIdsDto(trakt = 1)))
        assertEquals(4, findMovieRating(ratings, TraktIdsDto(imdb = "TT0000002")))
        assertEquals(4, findMovieRating(ratings, TraktIdsDto(tmdb = 22)))
    }

    @Test
    fun `findMovieRating returns null for unknown movies and out of range ratings`() {
        assertNull(findMovieRating(ratings, TraktIdsDto(imdb = "tt9999999")))
        assertNull(findMovieRating(ratings, TraktIdsDto()))
        val invalid = listOf(TraktRatingItemDto(rating = 0, movie = TraktMovieDto(ids = TraktIdsDto(trakt = 3))))
        assertNull(findMovieRating(invalid, TraktIdsDto(trakt = 3)))
    }

    @Test
    fun `isMovieRatingAccepted requires an added movie and no not found entries`() {
        assertTrue(isMovieRatingAccepted(TraktRatingsAddResponseDto(added = TraktListMutationCountDto(movies = 1))))
        assertFalse(isMovieRatingAccepted(TraktRatingsAddResponseDto(added = TraktListMutationCountDto(movies = 0))))
        assertFalse(
            isMovieRatingAccepted(
                TraktRatingsAddResponseDto(
                    added = TraktListMutationCountDto(movies = 0),
                    notFound = TraktListMutationNotFoundDto(movies = listOf(TraktMovieDto(ids = TraktIdsDto(imdb = "tt1"))))
                )
            )
        )
        assertFalse(isMovieRatingAccepted(null))
    }
}
