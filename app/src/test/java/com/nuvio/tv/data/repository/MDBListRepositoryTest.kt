package com.nuvio.tv.data.repository

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.MDBListSettingsDataStore
import com.nuvio.tv.data.remote.api.MDBListApi
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingItemDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListRatingResponseDto
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MDBListSettings
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.PosterShape
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import retrofit2.Response

class MDBListRepositoryTest {

    @Test
    fun `anime id is mapped through arm before requesting mdblist rating`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { settings.settings } returns flowOf(
            MDBListSettings(enabled = true, apiKey = "test-key")
        )
        coEvery {
            tmdbService.ensureTmdbIdForEnrichment("mal:62322", "series")
        } returns "298994"
        coEvery {
            tmdbService.tmdbToImdb(298994, "series")
        } returns "tt38262097"
        coEvery {
            api.getRating(
                mediaType = "show",
                ratingType = "imdb",
                apiKey = "test-key",
                body = any()
            )
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(rating = 8.4))
            )
        )
        val repository = MDBListRepository(api, settings, tmdbService)

        val rating = repository.getImdbRatingForItem("mal:62322", "series")

        assertEquals(8.4, rating ?: 0.0, 0.0)
        coVerify(exactly = 1) {
            tmdbService.ensureTmdbIdForEnrichment("mal:62322", "series")
            tmdbService.tmdbToImdb(298994, "series")
        }
    }

    @Test
    fun `fallback anime id is used when resolved meta id cannot be mapped`() = runTest {
        val api = mockk<MDBListApi>()
        val settings = mockk<MDBListSettingsDataStore>()
        val tmdbService = mockk<TmdbService>()
        every { settings.settings } returns flowOf(
            MDBListSettings(
                enabled = true,
                apiKey = "test-key",
                showTrakt = false,
                showTmdb = false,
                showLetterboxd = false,
                showTomatoes = false,
                showAudience = false,
                showMetacritic = false,
                showMal = false
            )
        )
        coEvery {
            tmdbService.ensureTmdbIdForEnrichment("addon:resolved-title", "series")
        } returns null
        coEvery {
            tmdbService.ensureTmdbIdForEnrichment("mal:62322", "series")
        } returns "298994"
        coEvery { tmdbService.tmdbToImdb(298994, "series") } returns "tt38262097"
        coEvery {
            api.getRating("show", "imdb", "test-key", any())
        } returns Response.success(
            MDBListRatingResponseDto(
                ratings = listOf(MDBListRatingItemDto(rating = 8.4))
            )
        )
        val repository = MDBListRepository(api, settings, tmdbService)
        val meta = Meta(
            id = "addon:resolved-title",
            type = ContentType.SERIES,
            name = "Test",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList()
        )

        val result = repository.getRatingsForMeta(meta, "mal:62322", "series")

        assertEquals(8.4, result?.ratings?.imdb ?: 0.0, 0.0)
        coVerify(exactly = 1) {
            tmdbService.ensureTmdbIdForEnrichment("addon:resolved-title", "series")
            tmdbService.ensureTmdbIdForEnrichment("mal:62322", "series")
        }
    }
}
