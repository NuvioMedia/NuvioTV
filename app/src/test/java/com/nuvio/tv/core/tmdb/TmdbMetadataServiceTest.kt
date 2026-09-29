package com.nuvio.tv.core.tmdb

import com.nuvio.tv.data.remote.api.TmdbAggregateCastMember
import com.nuvio.tv.data.remote.api.TmdbAggregateCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbAggregateRole
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbCastMember
import com.nuvio.tv.data.remote.api.TmdbCollectionPart
import com.nuvio.tv.data.remote.api.TmdbCollectionResponse
import com.nuvio.tv.data.remote.api.TmdbCompany
import com.nuvio.tv.data.remote.api.TmdbCompanyDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbCreatedBy
import com.nuvio.tv.data.remote.api.TmdbCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbCrewMember
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResponse
import com.nuvio.tv.data.remote.api.TmdbDiscoverResult
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.data.remote.api.TmdbImagesResponse
import com.nuvio.tv.data.remote.api.TmdbMovieReleaseDatesResponse
import com.nuvio.tv.data.remote.api.TmdbNetwork
import com.nuvio.tv.data.remote.api.TmdbNetworkDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonCreditCast
import com.nuvio.tv.data.remote.api.TmdbPersonCreditsResponse
import com.nuvio.tv.data.remote.api.TmdbPersonResponse
import com.nuvio.tv.data.remote.api.TmdbSeasonResponse
import com.nuvio.tv.data.remote.api.TmdbTvContentRatingsResponse
import com.nuvio.tv.data.remote.api.TmdbVideosResponse
import com.nuvio.tv.domain.model.ContentType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response
import java.util.concurrent.atomic.AtomicInteger

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
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

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
    fun `fetchEnrichment formats ongoing tv release range`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 20,
                name = "Ongoing Show",
                firstAirDate = "2012-09-20",
                lastAirDate = "2024-03-10",
                status = "Returning Series"
            )
        )
        coEvery { api.getTvAggregateCredits(any(), any(), any()) } returns Response.success(TmdbAggregateCreditsResponse())
        coEvery { api.getTvImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getTvContentRatings(any(), any()) } returns Response.success(TmdbTvContentRatingsResponse())
        coEvery { api.getTvVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 20))

        val service = TmdbMetadataService(api)

        val enrichment = service.fetchEnrichment(
            tmdbId = "20",
            contentType = ContentType.SERIES,
            language = "en"
        )

        assertEquals("2012-", enrichment?.releaseInfo)
    }

    @Test
    fun `fetchEnrichment formats ended tv release range`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(any(), any(), any()) } returns Response.success(
            TmdbDetailsResponse(
                id = 21,
                name = "Ended Show",
                firstAirDate = "2012-09-20",
                lastAirDate = "2019-05-19",
                status = "Ended"
            )
        )
        coEvery { api.getTvAggregateCredits(any(), any(), any()) } returns Response.success(TmdbAggregateCreditsResponse())
        coEvery { api.getTvImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getTvContentRatings(any(), any()) } returns Response.success(TmdbTvContentRatingsResponse())
        coEvery { api.getTvVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 21))

        val service = TmdbMetadataService(api)

        val enrichment = service.fetchEnrichment(
            tmdbId = "21",
            contentType = ContentType.SERIES,
            language = "en"
        )

        assertEquals("2012-2019", enrichment?.releaseInfo)
    }

    @Test
    fun `fetchEnrichment deduplicates concurrent requests for same key`() = runTest {
        val api = mockk<TmdbApi>()
        val gate = CompletableDeferred<Unit>()
        var detailsCalls = 0
        var creditsCalls = 0
        var imagesCalls = 0
        var releaseCalls = 0

        coEvery { api.getMovieDetails(any(), any(), any()) } coAnswers {
            detailsCalls += 1
            gate.await()
            Response.success(TmdbDetailsResponse(id = 10, title = "Movie", overview = "Synopsis"))
        }
        coEvery { api.getMovieCredits(any(), any(), any()) } coAnswers {
            creditsCalls += 1
            Response.success(TmdbCreditsResponse())
        }
        coEvery { api.getMovieImages(any(), any(), any()) } coAnswers {
            imagesCalls += 1
            Response.success(TmdbImagesResponse())
        }
        coEvery { api.getMovieReleaseDates(any(), any()) } coAnswers {
            releaseCalls += 1
            Response.success(TmdbMovieReleaseDatesResponse())
        }
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api, StandardTestDispatcher(testScheduler))

        val first = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }
        val second = async { service.fetchEnrichment(tmdbId = "10", contentType = ContentType.MOVIE, language = "en") }

        advanceUntilIdle()
        assertEquals(1, detailsCalls)

        gate.complete(Unit)
        val results = awaitAll(first, second)

        assertEquals(1, detailsCalls)
        assertEquals(1, creditsCalls)
        assertEquals(1, imagesCalls)
        assertEquals(1, releaseCalls)
        assertEquals(results[0], results[1])
    }

    @Test
    fun `episode enrichment fetches seasons with bounded concurrency`() = runTest {
        val api = mockk<TmdbApi>()
        val releaseRequests = CompletableDeferred<Unit>()
        val firstBatchStarted = CompletableDeferred<Unit>()
        val callCount = AtomicInteger()
        val activeRequests = AtomicInteger()
        val maximumActiveRequests = AtomicInteger()

        coEvery { api.getTvSeasonDetails(any(), any(), any(), any()) } coAnswers {
            val season = arg<Int>(1)
            val active = activeRequests.incrementAndGet()
            maximumActiveRequests.updateAndGet { current -> maxOf(current, active) }
            if (callCount.incrementAndGet() == 4) {
                firstBatchStarted.complete(Unit)
            }

            try {
                releaseRequests.await()
                Response.success(
                    TmdbSeasonResponse(
                        seasonNumber = season,
                        episodes = listOf(
                            TmdbEpisode(
                                episodeNumber = 1,
                                name = "Season $season premiere"
                            )
                        )
                    )
                )
            } finally {
                activeRequests.decrementAndGet()
            }
        }

        val service = TmdbMetadataService(api)
        val resultDeferred = async(Dispatchers.Default) {
            service.fetchEpisodeEnrichment(
                tmdbId = "42",
                seasonNumbers = (1..6).toList(),
                language = "en"
            )
        }

        firstBatchStarted.await()
        assertEquals(4, callCount.get())
        assertEquals(4, maximumActiveRequests.get())

        releaseRequests.complete(Unit)
        val result = resultDeferred.await()

        assertEquals(6, callCount.get())
        assertEquals(6, result.size)
        assertEquals("Season 6 premiere", result[6 to 1]?.title)
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
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7),
                withStatus = arg(16)
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
            api.discoverTv(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } answers {
            tvCalls += TvDiscoverCall(
                sortBy = arg(3),
                withCompanies = arg(4),
                withNetworks = arg(5),
                firstAirDateLte = arg(6),
                voteCountGte = arg(7),
                withStatus = arg(16)
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
        assertTrue(tvCalls.all { it.withStatus == "0|3|4" })
        assertNull(tvCalls.firstOrNull { it.sortBy == "popularity.desc" }?.voteCountGte)
        assertEquals(200, tvCalls.first { it.sortBy == "vote_average.desc" }.voteCountGte)
    }

    @Test
    fun `network browse falls back CJK titles to English`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery {
            api.discoverTv(any(), "tr-TR", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(
            TmdbDiscoverResponse(
                results = listOf(
                    TmdbDiscoverResult(
                        id = 57775,
                        name = "ちびまる子ちゃん",
                        originalName = "ちびまる子ちゃん",
                        posterPath = "/maruko.jpg",
                        firstAirDate = "1990-01-07"
                    ),
                    TmdbDiscoverResult(
                        id = 37854,
                        name = "One Piece",
                        originalName = "ワンピース",
                        posterPath = "/op.jpg",
                        firstAirDate = "1999-10-20"
                    )
                )
            )
        )
        coEvery {
            api.discoverTv(any(), "en", any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns Response.success(
            TmdbDiscoverResponse(
                results = listOf(
                    TmdbDiscoverResult(
                        id = 57775,
                        name = "Chibi Maruko-chan",
                        originalName = "ちびまる子ちゃん",
                        posterPath = "/maruko.jpg"
                    )
                )
            )
        )

        val service = TmdbMetadataService(api)
        val page = service.fetchEntityRailPage(
            entityKind = TmdbEntityKind.NETWORK,
            entityId = 1,
            mediaType = TmdbEntityMediaType.TV,
            railType = TmdbEntityRailType.POPULAR,
            language = "tr-TR",
            page = 1
        )

        assertEquals("Chibi Maruko-chan", page.items.single { it.id == "tmdb:57775" }.name)
        assertEquals("One Piece", page.items.single { it.id == "tmdb:37854" }.name)
    }

    @Test
    fun `containsCjkOrHangul detects Asian scripts correctly`() {
        assertTrue(containsCjkOrHangul("木村拓哉"))
        assertTrue(containsCjkOrHangul("田中 敦子"))
        assertTrue(containsCjkOrHangul("김수현"))
        assertTrue(containsCjkOrHangul("成龙"))
        assertFalse(containsCjkOrHangul("Scarlett Johansson"))
        assertFalse(containsCjkOrHangul("Tom Hanks"))
        assertFalse(containsCjkOrHangul("12345"))
    }

    @Test
    fun `resolveDisplayLabel falls back JP kanji to romaji or english for non-CJK locales`() {
        assertEquals(
            "Takuya Kimura",
            resolveDisplayLabel("木村拓哉", "Takuya Kimura", null, "tr-TR")
        )
        assertEquals(
            "Takuya Kimura",
            resolveDisplayLabel("木村拓哉", "木村拓哉", "Takuya Kimura", "tr-TR")
        )
        assertEquals(
            "Tsuyoshi Kusanagi",
            resolveDisplayLabel("草彅剛", "Tsuyoshi Kusanagi", null, "pl-PL")
        )
        assertEquals(
            "Atsuko Tanaka",
            resolveDisplayLabel("田中敦子", "田中敦子", "Atsuko Tanaka", "de-DE")
        )
    }

    @Test
    fun `resolveDisplayLabel keeps kanji when user language is Japanese`() {
        assertEquals(
            "木村拓哉",
            resolveDisplayLabel("木村拓哉", "Takuya Kimura", "Takuya Kimura", "ja-JP")
        )
        assertEquals(
            "田中敦子",
            resolveDisplayLabel("田中敦子", "田中敦子", "Atsuko Tanaka", "ja")
        )
    }

    @Test
    fun `resolveDisplayLabel falls back Hangul to Latin for non-Korean locales`() {
        assertEquals(
            "Kim Soo-hyun",
            resolveDisplayLabel("김수현", "Kim Soo-hyun", null, "de-DE")
        )
        assertEquals(
            "Kim Soo-hyun",
            resolveDisplayLabel("김수현", "김수현", "Kim Soo-hyun", "fr-FR")
        )
    }

    @Test
    fun `resolveDisplayLabel keeps Hangul when user language is Korean`() {
        assertEquals(
            "김수현",
            resolveDisplayLabel("김수현", "Kim Soo-hyun", "Kim Soo-hyun", "ko-KR")
        )
    }

    @Test
    fun `resolveDisplayLabel falls back Chinese hanzi to stage name for non-Chinese locales`() {
        assertEquals(
            "Jackie Chan",
            resolveDisplayLabel("成龙", "Jackie Chan", null, "es-ES")
        )
        assertEquals(
            "Jackie Chan",
            resolveDisplayLabel("成龙", "成龙", "Jackie Chan", "en-US")
        )
    }

    @Test
    fun `resolveDisplayLabel keeps hanzi when user language is Chinese`() {
        assertEquals(
            "成龙",
            resolveDisplayLabel("成龙", "Jackie Chan", "Jackie Chan", "zh-CN")
        )
    }

    @Test
    fun `resolveDisplayLabel leaves already-Latin names unchanged`() {
        assertEquals(
            "Scarlett Johansson",
            resolveDisplayLabel("Scarlett Johansson", "Scarlett Johansson", null, "tr-TR")
        )
        assertEquals(
            "Tom Hanks",
            resolveDisplayLabel("Tom Hanks", "Tom Hanks", null, "ja-JP")
        )
    }

    @Test
    fun `resolveDisplayLabel handles null and blank inputs`() {
        assertEquals("Takuya Kimura", resolveDisplayLabel(null, "Takuya Kimura", null, "tr-TR"))
        assertEquals("Scarlett Johansson", resolveDisplayLabel("Scarlett Johansson", null, null, "tr-TR"))
        assertNull(resolveDisplayLabel(null, null, null, "tr-TR"))
        assertEquals(
            "Takuya Kimura",
            resolveDisplayLabel("  木村拓哉  ", "Takuya Kimura", null, "fr-FR")
        )
    }

    @Test
    fun `fetchEnrichment falls back Japanese movie cast names to English for Turkish locale`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(100, any(), "tr-TR") } returns Response.success(
            TmdbDetailsResponse(id = 100, title = "Ghost in the Shell")
        )
        coEvery { api.getMovieCredits(100, any(), "tr-TR") } returns Response.success(
            TmdbCreditsResponse(
                cast = listOf(
                    TmdbCastMember(
                        id = 1,
                        name = "田中敦子",
                        originalName = "田中敦子",
                        character = "Motoko Kusanagi",
                        profilePath = "/tanaka.jpg"
                    ),
                    TmdbCastMember(
                        id = 2,
                        name = "Scarlett Johansson",
                        originalName = "Scarlett Johansson",
                        character = "Major",
                        profilePath = "/sj.jpg"
                    )
                ),
                crew = listOf(
                    TmdbCrewMember(
                        id = 3,
                        name = "押井守",
                        originalName = "押井守",
                        job = "Director",
                        profilePath = "/oshii.jpg"
                    )
                )
            )
        )
        coEvery { api.getMovieCredits(100, any(), "en-US") } returns Response.success(
            TmdbCreditsResponse(
                cast = listOf(
                    TmdbCastMember(
                        id = 1,
                        name = "Atsuko Tanaka",
                        originalName = "田中敦子",
                        character = "Motoko Kusanagi",
                        profilePath = "/tanaka.jpg"
                    ),
                    TmdbCastMember(
                        id = 2,
                        name = "Scarlett Johansson",
                        originalName = "Scarlett Johansson",
                        character = "Major",
                        profilePath = "/sj.jpg"
                    )
                ),
                crew = listOf(
                    TmdbCrewMember(
                        id = 3,
                        name = "Mamoru Oshii",
                        originalName = "押井守",
                        job = "Director",
                        profilePath = "/oshii.jpg"
                    )
                )
            )
        )
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 100))

        val service = TmdbMetadataService(api)
        val enrichment = service.fetchEnrichment(
            tmdbId = "100",
            contentType = ContentType.MOVIE,
            language = "tr-TR"
        )

        assertNotNull(enrichment)
        val castNames = enrichment!!.castMembers.map { it.name }
        assertEquals(listOf("Atsuko Tanaka", "Scarlett Johansson"), castNames)

        val directorNames = enrichment.directorMembers.map { it.name }
        assertEquals(listOf("Mamoru Oshii"), directorNames)
    }

    @Test
    fun `fetchEnrichment falls back CJK movie title to English`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(10, any(), "tr-TR") } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                title = "チェンソーマン総集篇 後篇",
                originalTitle = "チェンソーマン総集篇 後篇",
                originalLanguage = "ja"
            )
        )
        coEvery { api.getMovieDetails(10, any(), "en") } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                title = "Chainsaw Man - The Compilation Movie",
                originalTitle = "チェンソーマン総集篇 後篇",
                originalLanguage = "ja"
            )
        )
        coEvery { api.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api)
        val enrichment = service.fetchEnrichment(
            tmdbId = "10",
            contentType = ContentType.MOVIE,
            language = "tr-TR"
        )

        assertEquals("Chainsaw Man - The Compilation Movie", enrichment?.localizedTitle)
        coVerify(exactly = 1) { api.getMovieDetails(10, any(), "en") }
    }

    @Test
    fun `fetchEnrichment keeps CJK movie title when language is Japanese`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getMovieDetails(10, any(), "ja-JP") } returns Response.success(
            TmdbDetailsResponse(
                id = 10,
                title = "チェンソーマン総集篇 後篇",
                originalTitle = "チェンソーマン総集篇 後篇",
                originalLanguage = "ja"
            )
        )
        coEvery { api.getMovieCredits(any(), any(), any()) } returns Response.success(TmdbCreditsResponse())
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getMovieReleaseDates(any(), any()) } returns Response.success(TmdbMovieReleaseDatesResponse())
        coEvery { api.getMovieVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 10))

        val service = TmdbMetadataService(api)
        val enrichment = service.fetchEnrichment(
            tmdbId = "10",
            contentType = ContentType.MOVIE,
            language = "ja-JP"
        )

        assertEquals("チェンソーマン総集篇 後篇", enrichment?.localizedTitle)
        coVerify(exactly = 0) { api.getMovieDetails(10, any(), "en") }
    }

    @Test
    fun `fetchEnrichment falls back TV aggregate credits cast and creator names to English for Polish locale`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getTvDetails(255358, any(), "pl-PL") } returns Response.success(
            TmdbDetailsResponse(
                id = 255358,
                name = "THE GHOST IN THE SHELL",
                createdBy = listOf(
                    TmdbCreatedBy(id = 10, name = "士郎正宗", originalName = "士郎正宗")
                )
            )
        )
        coEvery { api.getTvAggregateCredits(255358, any(), "pl-PL") } returns Response.success(
            TmdbAggregateCreditsResponse(
                cast = listOf(
                    TmdbAggregateCastMember(
                        id = 1,
                        name = "田中敦子",
                        originalName = "田中敦子",
                        roles = listOf(TmdbAggregateRole(character = "Motoko Kusanagi", episodeCount = 12))
                    )
                )
            )
        )
        coEvery { api.getTvAggregateCredits(255358, any(), "en-US") } returns Response.success(
            TmdbAggregateCreditsResponse(
                cast = listOf(
                    TmdbAggregateCastMember(
                        id = 1,
                        name = "Atsuko Tanaka",
                        originalName = "田中敦子",
                        roles = listOf(TmdbAggregateRole(character = "Motoko Kusanagi", episodeCount = 12))
                    )
                )
            )
        )
        coEvery { api.getTvDetails(255358, any(), "en-US") } returns Response.success(
            TmdbDetailsResponse(
                id = 255358,
                name = "THE GHOST IN THE SHELL",
                createdBy = listOf(
                    TmdbCreatedBy(id = 10, name = "Masamune Shirow", originalName = "士郎正宗")
                )
            )
        )
        coEvery { api.getTvImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())
        coEvery { api.getTvContentRatings(any(), any()) } returns Response.success(TmdbTvContentRatingsResponse())
        coEvery { api.getTvVideos(any(), any(), any()) } returns Response.success(TmdbVideosResponse(id = 255358))

        val service = TmdbMetadataService(api)
        val enrichment = service.fetchEnrichment(
            tmdbId = "255358",
            contentType = ContentType.SERIES,
            language = "pl-PL"
        )

        assertNotNull(enrichment)
        assertEquals(listOf("Atsuko Tanaka"), enrichment!!.castMembers.map { it.name })
        assertEquals(listOf("Masamune Shirow"), enrichment.directorMembers.map { it.name })
    }

    @Test
    fun `fetchPersonDetail falls back Japanese person name to English for Turkish locale`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getPersonDetails(500, any(), "tr-TR") } returns Response.success(
            TmdbPersonResponse(
                id = 500,
                name = "田中敦子",
                originalName = "田中敦子",
                biography = ""
            )
        )
        coEvery { api.getPersonDetails(500, any(), "en") } returns Response.success(
            TmdbPersonResponse(
                id = 500,
                name = "Atsuko Tanaka",
                originalName = "田中敦子",
                biography = "Atsuko Tanaka was a Japanese voice actress."
            )
        )
        coEvery { api.getPersonCombinedCredits(500, any(), "tr-TR") } returns Response.success(null)

        val service = TmdbMetadataService(api)
        val detail = service.fetchPersonDetail(personId = 500, language = "tr-TR")

        assertNotNull(detail)
        assertEquals("Atsuko Tanaka", detail?.name)
        assertEquals("Atsuko Tanaka was a Japanese voice actress.", detail?.biography)
    }

    @Test
    fun `fetchPersonDetail falls back CJK filmography titles to English`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getPersonDetails(500, any(), "pl-PL") } returns Response.success(
            TmdbPersonResponse(
                id = 500,
                name = "Atsuko Tanaka",
                originalName = "田中敦子"
            )
        )
        coEvery { api.getPersonDetails(500, any(), "en") } returns Response.success(
            TmdbPersonResponse(
                id = 500,
                name = "Atsuko Tanaka",
                originalName = "田中敦子"
            )
        )
        coEvery { api.getPersonCombinedCredits(500, any(), "pl-PL") } returns Response.success(
            TmdbPersonCreditsResponse(
                cast = listOf(
                    TmdbPersonCreditCast(
                        id = 255358,
                        name = "攻殻機動隊 STAND ALONE COMPLEX",
                        originalName = "攻殻機動隊 STAND ALONE COMPLEX",
                        mediaType = "tv",
                        posterPath = "/poster.jpg",
                        firstAirDate = "2002-10-01"
                    ),
                    TmdbPersonCreditCast(
                        id = 99,
                        title = "Make My Day",
                        originalTitle = "Make My Day",
                        mediaType = "movie",
                        posterPath = "/mmd.jpg",
                        releaseDate = "2023-01-01"
                    )
                )
            )
        )
        coEvery { api.getPersonCombinedCredits(500, any(), "en") } returns Response.success(
            TmdbPersonCreditsResponse(
                cast = listOf(
                    TmdbPersonCreditCast(
                        id = 255358,
                        name = "Ghost in the Shell: Stand Alone Complex",
                        originalName = "攻殻機動隊 STAND ALONE COMPLEX",
                        mediaType = "tv",
                        posterPath = "/poster.jpg"
                    )
                )
            )
        )

        val service = TmdbMetadataService(api)
        val detail = service.fetchPersonDetail(personId = 500, language = "pl-PL")

        assertNotNull(detail)
        assertEquals("Ghost in the Shell: Stand Alone Complex", detail?.tvCredits?.single()?.name)
        assertEquals("Make My Day", detail?.movieCredits?.single()?.name)
    }

    @Test
    fun `fetchMovieCollection falls back CJK titles to English`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCollectionDetails(10, any(), "tr-TR") } returns Response.success(
            TmdbCollectionResponse(
                id = 10,
                name = "チェンソーマン シリーズ",
                parts = listOf(
                    TmdbCollectionPart(
                        id = 1,
                        title = "Movie: Reze Arc",
                        originalTitle = "チェンソーマン レゼ編",
                        releaseDate = "2025-09-19",
                        posterPath = "/reze.jpg"
                    ),
                    TmdbCollectionPart(
                        id = 2,
                        title = "チェンソーマン総集篇 前篇",
                        originalTitle = "チェンソーマン総集篇 前篇",
                        releaseDate = "2023-09-22",
                        posterPath = "/part1.jpg"
                    ),
                    TmdbCollectionPart(
                        id = 3,
                        title = "チェンソーマン総集篇 後篇",
                        originalTitle = "チェンソーマン総集篇 後篇",
                        releaseDate = "2023-09-22",
                        posterPath = "/part2.jpg"
                    )
                )
            )
        )
        coEvery { api.getCollectionDetails(10, any(), "en") } returns Response.success(
            TmdbCollectionResponse(
                id = 10,
                name = "Chainsaw Man Collection",
                parts = listOf(
                    TmdbCollectionPart(id = 1, title = "Chainsaw Man – The Movie: Reze Arc"),
                    TmdbCollectionPart(id = 2, title = "Chainsaw Man Compilation Movie 1"),
                    TmdbCollectionPart(id = 3, title = "Chainsaw Man Compilation Movie 2")
                )
            )
        )
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())

        val service = TmdbMetadataService(api)
        val collection = service.fetchMovieCollection(collectionId = 10, language = "tr-TR")

        assertEquals("Chainsaw Man Collection", collection.name)
        assertEquals("Movie: Reze Arc", collection.items.single { it.id == "tmdb:1" }.name)
        assertEquals("Chainsaw Man Compilation Movie 1", collection.items.single { it.id == "tmdb:2" }.name)
        assertEquals("Chainsaw Man Compilation Movie 2", collection.items.single { it.id == "tmdb:3" }.name)
        coVerify(exactly = 1) { api.getCollectionDetails(10, any(), "en") }
    }

    @Test
    fun `fetchMovieCollection keeps CJK titles when language is Japanese`() = runTest {
        val api = mockk<TmdbApi>()
        coEvery { api.getCollectionDetails(10, any(), "ja-JP") } returns Response.success(
            TmdbCollectionResponse(
                id = 10,
                name = "チェンソーマン シリーズ",
                parts = listOf(
                    TmdbCollectionPart(
                        id = 2,
                        title = "チェンソーマン総集篇 前篇",
                        originalTitle = "チェンソーマン総集篇 前篇",
                        posterPath = "/part1.jpg"
                    )
                )
            )
        )
        coEvery { api.getMovieImages(any(), any(), any()) } returns Response.success(TmdbImagesResponse())

        val service = TmdbMetadataService(api)
        val collection = service.fetchMovieCollection(collectionId = 10, language = "ja-JP")

        assertEquals("チェンソーマン シリーズ", collection.name)
        assertEquals("チェンソーマン総集篇 前篇", collection.items.single().name)
        coVerify(exactly = 0) { api.getCollectionDetails(10, any(), "en") }
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
        val voteCountGte: Int?,
        val withStatus: String?
    )
}
