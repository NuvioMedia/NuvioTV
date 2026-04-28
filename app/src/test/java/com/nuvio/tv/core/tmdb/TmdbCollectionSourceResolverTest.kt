package com.nuvio.tv.core.tmdb

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbCollectionResponse
import com.nuvio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbListDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbListItem
import com.nuvio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nuvio.tv.domain.model.TmdbCollectionFilters
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TmdbSettings
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TmdbCollectionSourceResolverTest {
    private val settings = mockk<TmdbSettingsDataStore> {
        every { this@mockk.settings } returns flowOf(TmdbSettings(language = "en"))
    }

    @Test
    fun `parseTmdbId accepts ids and tmdb urls`() {
        val resolver = TmdbCollectionSourceResolver(mockk(relaxed = true), settings)

        assertEquals(123, resolver.parseTmdbId("123"))
        assertEquals(456, resolver.parseTmdbId("https://www.themoviedb.org/list/456-marvel"))
        assertEquals(789, resolver.parseTmdbId("https://www.themoviedb.org/collection/789"))
    }

    @Test
    fun `list source maps items and pagination`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getListDetails(44, any(), "en", 2) } returns Response.success(
            TmdbListDetailsResponse(
                id = 44,
                name = "A List",
                items = listOf(
                    TmdbListItem(
                        id = 10,
                        title = "Movie",
                        mediaType = "movie",
                        posterPath = "/poster.jpg",
                        releaseDate = "2024-01-01",
                        voteAverage = 7.5
                    ),
                    TmdbListItem(
                        id = 11,
                        name = "Show",
                        mediaType = "tv",
                        posterPath = "/show.jpg",
                        firstAirDate = "2023-02-03",
                        voteAverage = 8.0
                    )
                ),
                page = 2,
                totalPages = 3
            )
        )
        val resolver = TmdbCollectionSourceResolver(api, settings)
        val result = resolver.resolve(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.LIST,
                title = "Imported",
                tmdbId = 44
            ),
            page = 2
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("Imported", result.data.catalogName)
        assertEquals(2, result.data.items.size)
        assertEquals("tmdb:10", result.data.items[0].id)
        assertEquals("series", result.data.items[1].apiType)
        assertEquals(2, result.data.currentPage)
        assertTrue(result.data.hasMore)
    }

    @Test
    fun `list import metadata uses tmdb list name`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getListDetails(44, any(), "en", 1) } returns Response.success(
            TmdbListDetailsResponse(
                id = 44,
                name = "Weekend Watchlist"
            )
        )
        val resolver = TmdbCollectionSourceResolver(api, settings)

        val metadata = resolver.listImportMetadata(44)

        assertEquals("Weekend Watchlist", metadata.title)
    }

    @Test
    fun `collection import metadata uses name and poster cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCollectionDetails(10, any(), "en") } returns Response.success(
            TmdbCollectionResponse(
                id = 10,
                name = "Star Wars Collection",
                posterPath = "/collection.jpg",
                backdropPath = "/collection-backdrop.jpg"
            )
        )
        val resolver = TmdbCollectionSourceResolver(api, settings)

        val metadata = resolver.collectionImportMetadata(10)

        assertEquals("Star Wars Collection", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/collection.jpg", metadata.coverImageUrl)
    }

    @Test
    fun `company import metadata uses name and logo cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCompanyDetails(420, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(
                id = 420,
                name = "Marvel Studios",
                logoPath = "/marvel.png"
            )
        )
        val resolver = TmdbCollectionSourceResolver(api, settings)

        val metadata = resolver.companyImportMetadata(420)

        assertEquals("Marvel Studios", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/marvel.png", metadata.coverImageUrl)
    }

    @Test
    fun `network import metadata uses name and logo cover`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getNetworkDetails(2552, any()) } returns Response.success(
            TmdbNetworkDetailsResponse(
                id = 2552,
                name = "Apple TV+",
                logoPath = "/apple.png"
            )
        )
        val resolver = TmdbCollectionSourceResolver(api, settings)

        val metadata = resolver.networkImportMetadata(2552)

        assertEquals("Apple TV+", metadata.title)
        assertEquals("https://image.tmdb.org/t/p/w500/apple.png", metadata.coverImageUrl)
    }

    @Test
    fun `discover source forwards advanced movie filters`() = runTest {
        val api = mockk<TmdbApi>()
        var capturedGenres: String? = null
        var capturedDateGte: String? = null
        var capturedRating: Double? = null
        var capturedKeywords: String? = null
        var capturedCompanies: String? = null
        coEvery {
            api.discoverMovies(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()
            )
        } answers {
            capturedCompanies = arg(4)
            capturedGenres = arg(7)
            capturedDateGte = arg(8)
            capturedRating = arg(9)
            capturedKeywords = arg(13)
            Response.success(
                TmdbDiscoverResponse(
                    page = 1,
                    totalPages = 1,
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 99,
                            title = "Filtered",
                            posterPath = "/filtered.jpg",
                            releaseDate = "2020-01-01"
                        )
                    )
                )
            )
        }
        val resolver = TmdbCollectionSourceResolver(api, settings)
        val result = resolver.resolve(
            TmdbCollectionSource(
                sourceType = TmdbCollectionSourceType.DISCOVER,
                title = "Filtered",
                mediaType = TmdbCollectionMediaType.MOVIE,
                filters = TmdbCollectionFilters(
                    withGenres = "28,12",
                    releaseDateGte = "2020-01-01",
                    voteAverageGte = 7.0,
                    withKeywords = "9715",
                    withCompanies = "420"
                )
            )
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("28,12", capturedGenres)
        assertEquals("2020-01-01", capturedDateGte)
        assertEquals(7.0, capturedRating)
        assertEquals("9715", capturedKeywords)
        assertEquals("420", capturedCompanies)
        assertEquals("tmdb:99", result.data.items.single().id)
    }
}
