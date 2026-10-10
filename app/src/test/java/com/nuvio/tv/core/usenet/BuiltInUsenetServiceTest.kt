package com.nuvio.tv.core.usenet

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.remote.api.TmdbApi
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

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

    @Test fun `lower priority indexers are only searched when higher ones find nothing`() = runTest {
        val first = UsenetIndexer(id = "a", name = "A", apiUrl = "https://a.test/api", priority = 1)
        val shared = UsenetIndexer(id = "b", name = "B", apiUrl = "https://b.test/api", priority = 1)
        val backup = UsenetIndexer(id = "c", name = "C", apiUrl = "https://c.test/api", priority = 2)
        val config = UsenetSourceConfiguration(enabled = true, providers = listOf(provider), indexers = listOf(backup, first, shared))
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

    @Test fun `disabled configuration never contacts indexers`() = runTest {
        val client = mockk<NewznabClient>()
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        assertTrue(service.search(UsenetSourceConfiguration(), "movie", "tt123", null, null).toList().isEmpty())
        coVerify(exactly = 0) { client.capabilities(any()) }
    }
}
