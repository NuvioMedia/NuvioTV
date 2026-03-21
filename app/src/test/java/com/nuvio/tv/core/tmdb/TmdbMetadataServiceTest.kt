package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCompany
import com.nuvio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbImagesResponse
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nuvio.tv.data.remote.api.TmdbNetwork
import com.nuvio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nuvio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class TmdbMetadataServiceTest {

    @Test
    fun `fetchEnrichment maps tmdb ids onto production and network companies`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                productionCompanies = listOf(
                    TmdbCompany(id = 55, name = "Acme Pictures", logoPath = "/company.png")
                ),
                networks = listOf(
                    TmdbNetwork(id = 77, name = "Prime TV", logoPath = "/network.png")
                )
            )
        )
        coEvery { api.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())

        val service = TmdbMetadataService(api)

        val enrichment = service.fetchEnrichment(
            tmdbId = "10",
            contentType = ContentType.MOVIE,
            language = "en"
        )

        assertNotNull(enrichment)
        assertEquals(55, enrichment?.productionCompanies?.firstOrNull()?.tmdbId)
        assertEquals(77, enrichment?.networks?.firstOrNull()?.tmdbId)
    }

    @Test
    fun `company browse requests movie then tv rails when source type is movie`() = runTest {
        val api = mockk<TmdbApi>()
        val movieCalls = mutableListOf<MovieDiscoverCall>()
        val tvCalls = mutableListOf<TvDiscoverCall>()

        coEvery { api.getCompanyDetails(99, any()) } returns Response.success(
            TmdbCompanyDetailsResponse(
                id = 99,
                name = "Acme Pictures",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } answers {
            movieCalls += MovieDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                releaseDateLte = arg(5),
                voteCountGte = arg(6)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = movieCalls.size,
                            title = "Movie ${movieCalls.size}",
                            posterPath = "/movie-${movieCalls.size}.jpg",
                            backdropPath = "/movie-bg-${movieCalls.size}.jpg",
                            releaseDate = "2024-01-01",
                            voteAverage = 7.4
                        )
                    )
                )
            )
        }
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 100 + tvCalls.size,
                            name = "Show ${tvCalls.size}",
                            posterPath = "/show-${tvCalls.size}.jpg",
                            backdropPath = "/show-bg-${tvCalls.size}.jpg",
                            firstAirDate = "2023-03-10",
                            voteAverage = 8.0
                        )
                    )
                )
            )
        }

        val service = TmdbMetadataService(api)

        val data = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.COMPANY,
            entityId = 99,
            sourceType = "movie",
            fallbackName = "Acme Pictures",
            language = "en"
        )

        assertNotNull(data)
        assertEquals(
            listOf(
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.MOVIE,
                TmdbEntityMediaType.TV,
                TmdbEntityMediaType.TV,
                TmdbEntityMediaType.TV
            ),
            data?.rails?.map { it.mediaType }
        )
        assertEquals(3, movieCalls.size)
        assertEquals(3, tvCalls.size)
        assertTrue(movieCalls.all { it.withCompanies == "99" })
        assertTrue(tvCalls.all { it.withCompanies == "99" })
        assertTrue(tvCalls.all { it.withNetworks == null })
        assertEquals(200, movieCalls.first { it.sortBy == "vote_average.desc" }.voteCountGte)
        assertTrue(movieCalls.first { it.sortBy == "primary_release_date.desc" }.releaseDateLte != null)
        assertTrue(tvCalls.first { it.sortBy == "first_air_date.desc" }.firstAirDateLte != null)
        assertTrue(data?.rails?.flatMap { it.items }.orEmpty().all { it.id.startsWith("tmdb:") })
    }

    @Test
    fun `network browse only requests tv rails and scopes by network id`() = runTest {
        val api = mockk<TmdbApi>()
        val tvCalls = mutableListOf<TvDiscoverCall>()

        coEvery { api.getNetworkDetails(77, any()) } returns Response.success(
            TmdbNetworkDetailsResponse(
                id = 77,
                name = "Prime TV",
                originCountry = "US"
            )
        )
        coEvery {
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7)
            )
            Response.success(
                TmdbDiscoverResponse(
                    results = listOf(
                        TmdbDiscoverResult(
                            id = 500 + tvCalls.size,
                            name = "Network Show ${tvCalls.size}",
                            posterPath = "/network-${tvCalls.size}.jpg",
                            backdropPath = "/network-bg-${tvCalls.size}.jpg",
                            firstAirDate = "2022-07-01",
                            voteAverage = 8.3
                        )
                    )
                )
            )
        }
        coEvery {
            api.discoverMovies(any(), any(), any(), any(), any(), any(), any())
        } throws AssertionError("movie discovery must not run for networks")

        val service = TmdbMetadataService(api)

        val data = service.fetchEntityBrowse(
            entityKind = TmdbEntityKind.NETWORK,
            entityId = 77,
            sourceType = "series",
            fallbackName = "Prime TV",
            language = "en"
        )

        assertNotNull(data)
        assertEquals(3, tvCalls.size)
        assertTrue(data?.rails?.all { it.mediaType == TmdbEntityMediaType.TV } == true)
        assertTrue(tvCalls.all { it.withNetworks == "77" })
        assertTrue(tvCalls.all { it.withCompanies == null })
        assertNull(tvCalls.firstOrNull { it.sortBy == "popularity.desc" }?.voteCountGte)
        assertEquals(200, tvCalls.first { it.sortBy == "vote_average.desc" }.voteCountGte)
    }

    private data class MovieDiscoverCall(
        val sortBy: String?,
        val withCompanies: String?,
        val releaseDateLte: String?,
        val voteCountGte: Int?
    )

    private data class TvDiscoverCall(
        val sortBy: String?,
        val withCompanies: String?,
        val withNetworks: String?,
        val firstAirDateLte: String?,
        val voteCountGte: Int?
    )
}
