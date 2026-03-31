package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedEpisodeItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRatedMovieItemDto
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TraktRatingServiceTest {

    private val service = TraktRatingService(
        traktApi = mockk(),
        traktAuthService = mockk(),
        profileManager = mockk(),
        traktSettingsDataStore = mockk()
    )

    @Test
    fun `buildRequestBody creates movie payload with one rating`() {
        val payload = service.buildRequestBody(
            item = TraktRatingItem.Movie(
                ids = TraktIdsDto(imdb = "tt1234567")
            ),
            rating = 9
        )

        assertEquals(9, payload.movies?.singleOrNull()?.rating)
        assertEquals("tt1234567", payload.movies?.singleOrNull()?.ids?.imdb)
        assertNull(payload.episodes)
    }

    @Test
    fun `buildRequestBody creates episode payload with trakt episode id`() {
        val payload = service.buildRequestBody(
            item = TraktRatingItem.Episode(
                ids = TraktIdsDto(trakt = 456),
                showTitle = "Show",
                season = 1,
                number = 2,
                episodeTitle = "Episode"
            ),
            rating = 7
        )

        assertEquals(7, payload.episodes?.singleOrNull()?.rating)
        assertEquals(456, payload.episodes?.singleOrNull()?.ids?.trakt)
        assertNull(payload.movies)
    }

    @Test
    fun `findMovieRating matches an existing trakt movie rating`() {
        val rating = service.findMovieRating(
            targetIds = TraktIdsDto(imdb = "tt1234567"),
            ratings = listOf(
                TraktRatedMovieItemDto(
                    rating = 8,
                    movie = TraktMovieDto(ids = TraktIdsDto(imdb = "tt1234567"))
                )
            )
        )

        assertEquals(8, rating)
    }

    @Test
    fun `findEpisodeRating matches an existing trakt episode rating`() {
        val rating = service.findEpisodeRating(
            targetIds = TraktIdsDto(tvdb = 321),
            ratings = listOf(
                TraktRatedEpisodeItemDto(
                    rating = 7,
                    episode = TraktEpisodeDto(ids = TraktIdsDto(tvdb = 321))
                )
            )
        )

        assertEquals(7, rating)
    }
}
