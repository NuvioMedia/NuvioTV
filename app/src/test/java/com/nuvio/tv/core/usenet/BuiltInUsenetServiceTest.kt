package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import retrofit2.Response

class BuiltInUsenetServiceTest {
    @Test fun `failed indexer does not cancel good results and episode suffixes are resolved`() = runTest {
        val good = UsenetIndexer(id = "good", name = "Good", apiUrl = "https://good.test/api")
        val bad = UsenetIndexer(id = "bad", name = "Bad", apiUrl = "https://bad.test/api")
        val config = UsenetSourceConfiguration(enabled = true,
            providers = listOf(UsenetProvider(name = "News", host = "news.test", username = "user", password = "pass")),
            indexers = listOf(good, bad))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        coEvery { client.capabilities(good) } returns NewznabCapabilities()
        coEvery { client.capabilities(bad) } throws IllegalStateException("secret-api-key")
        coEvery { client.search(good, any(), any()) } returns listOf(
            UsenetRelease("Show.S01E03.1080p", "https://good.test/nzb/1", indexerId = good.id, indexerName = good.name))
        val service = BuiltInUsenetService(client, tmdb, mockk<TmdbApi>())
        val results = service.search(config, "series", "tt123:1:3", null, null).toList()
        assertEquals(1, results.count { it.failure != null })
        assertFalse(results.mapNotNull { it.failure }.joinToString().contains("secret-api-key"))
        val stream = results.mapNotNull { it.group }.single().streams.single()
        assertTrue(stream.isUsenet())
        assertEquals(listOf("nntps://user:pass@news.test:563/20?priority=1"), stream.servers)
        coVerify { client.search(good, match { it.season == 1 && it.episode == 3 && it.imdbId == "tt123" }, any()) }
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any()) }
    }

    private val provider = UsenetProvider(name = "News", host = "news.test")
    private fun release(indexer: UsenetIndexer, title: String = "Movie.2020.1080p") =
        UsenetRelease(title, "${indexer.apiUrl}/nzb/$title", indexerId = indexer.id, indexerName = indexer.name)

    @Test fun `every indexer is searched by default and repeated releases are kept unless hidden`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(first, backup))
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any()) } returns listOf(release(first, "Movie.2020.1080p-GRP"))
        coEvery { client.search(backup, any(), any()) } returns
            listOf(release(backup, "Movie 2020 1080p-GRP"), release(backup, "Movie.2020.720p-GRP"))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        suspend fun names(config: UsenetSourceConfiguration) =
            service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams.map { it.title }
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie 2020 1080p-GRP", "Movie.2020.720p-GRP"), names(config))
        coVerify { client.search(backup, any(), any()) }
        // Hidden repeats keep the higher-priority indexer's copy.
        assertEquals(listOf("Movie.2020.1080p-GRP", "Movie.2020.720p-GRP"), names(config.copy(hideDuplicates = true)))
    }

    @Test fun `with fallback on lower priority indexers are only searched when higher ones find nothing`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val shared = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(backup, first, shared),
            indexerFallback = true)
        val client = mockk<NewznabClient>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities()
        coEvery { client.search(first, any(), any()) } returns listOf(release(first))
        coEvery { client.search(shared, any(), any()) } returns emptyList()
        coEvery { client.search(backup, any(), any()) } returns listOf(release(backup))
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        val streams = service.search(config, "movie", "tt1", null, null).toList().last().group!!.streams
        assertEquals(listOf("Usenet • A"), streams.map { it.name })
        coVerify { client.search(shared, any(), any()) }
        coVerify(exactly = 0) { client.capabilities(backup) }

        // A failed or empty higher priority falls through to the next one.
        coEvery { client.search(first, any(), any()) } throws IllegalStateException("down")
        val fallback = service.search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("Usenet • C"), fallback.mapNotNull { it.group }.last().streams.map { it.name })
    }

    @Test fun `failed metadata lookup only fails indexers that need a title search`() = runTest {
        val ids = UsenetIndexer(id = "ids", name = "Ids", apiUrl = "https://ids.test/api")
        val titles = UsenetIndexer(id = "titles", name = "Titles", apiUrl = "https://titles.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(titles, ids))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        coEvery { client.capabilities(ids) } returns NewznabCapabilities()
        coEvery { client.capabilities(titles) } returns NewznabCapabilities(movieParams = setOf("q"))
        coEvery { client.search(ids, any(), any()) } returns listOf(release(ids))
        coEvery { tmdb.ensureTmdbId(any(), any()) } throws IllegalStateException("tmdb down")
        val results = BuiltInUsenetService(client, tmdb, mockk<TmdbApi>()).search(config, "movie", "tt1", null, null).toList()
        assertEquals(listOf("Titles: indexer search failed. Check the API URL, key and limits."), results.mapNotNull { it.failure })
        assertEquals(listOf("Usenet • Ids"), results.mapNotNull { it.group }.last().streams.map { it.name })
    }

    @Test fun `tvdb id is looked up for indexers that only search tv by tvdb`() = runTest {
        val tvdbOnly = UsenetIndexer(id = "tvdb", name = "Tvdb", apiUrl = "https://tvdb.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(tvdbOnly))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(tvdbOnly) } returns NewznabCapabilities(tvParams = setOf("tvdbid", "season", "ep"))
        coEvery { client.search(tvdbOnly, any(), any()) } returns listOf(release(tvdbOnly, "Show.S01E02.1080p"))
        coEvery { tmdb.ensureTmdbId(any(), any()) } returns "1399"
        coEvery { tmdbApi.getTvDetails(1399, any(), any()) } throws java.io.IOException("offline")
        coEvery { tmdbApi.getTvExternalIds(1399, any()) } returns
            retrofit2.Response.success(com.nuvio.tv.data.remote.api.TmdbExternalIdsResponse(1399, "tt0944947", 121361))
        val results = BuiltInUsenetService(client, tmdb, tmdbApi).search(config, "series", "tt0944947:1:2", null, null).toList()
        assertEquals(listOf("Usenet • Tvdb"), results.mapNotNull { it.group }.last().streams.map { it.name })
        coVerify { client.search(tvdbOnly, match { it.tvdbId == "121361" && it.episode == 2 }, any()) }
    }

    @Test fun `disabled configuration never contacts indexers`() = runTest {
        val client = mockk<NewznabClient>()
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        assertTrue(service.search(UsenetSourceConfiguration(), "movie", "tt123", null, null).toList().isEmpty())
        coVerify(exactly = 0) { client.capabilities(any()) }
    }

    @Test fun `an empty id search is asked again by title`() = runTest {
        val indexer = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api")
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(indexer))
        val client = mockk<NewznabClient>()
        val tmdb = mockk<TmdbService>()
        val tmdbApi = mockk<TmdbApi>()
        coEvery { client.capabilities(any()) } returns NewznabCapabilities(movieParams = setOf("imdbid", "q"))
        coEvery { client.search(indexer, match { it.imdbId != null }, any()) } returns emptyList()
        coEvery { client.search(indexer, match { it.imdbId == null }, any()) } returns listOf(release(indexer, "The.Movie.2020.1080p"))
        coEvery { tmdb.ensureTmdbId("tt1", "movie") } returns "10"
        coEvery { tmdbApi.getMovieDetails(10, any(), any()) } returns Response.success(mockk<TmdbDetailsResponse>(relaxed = true) {
            every { title } returns "The Movie"
            every { releaseDate } returns "2020-05-01"
        })
        val service = BuiltInUsenetService(client, tmdb, tmdbApi)
        val streams = service.search(config, "movie", "tt1", null, null).toList().mapNotNull { it.group }.last().streams
        assertEquals(listOf("The.Movie.2020.1080p"), streams.map { it.title })
        coVerify { client.search(indexer, match { it.imdbId == null && it.tmdbId == null && it.title == "The Movie" && it.year == 2020 }, any()) }

        // A title search that also finds nothing is not repeated.
        coEvery { client.search(indexer, match { it.imdbId == null }, any()) } returns emptyList()
        assertTrue(service.search(config, "movie", "tt1", null, null).toList().none { it.group != null })
        coVerify(exactly = 2) { client.search(indexer, match { it.imdbId == null }, any()) }
    }
}
