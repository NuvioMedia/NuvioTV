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
        assertEquals(listOf("nntps://user:pass@news.test:563/20"), stream.servers)
        coVerify { client.search(good, match { it.season == 1 && it.episode == 3 && it.imdbId == "tt123" }, any()) }
        coVerify(exactly = 0) { tmdb.ensureTmdbId(any(), any()) }
    }

    @Test fun `disabled configuration never contacts indexers`() = runTest {
        val client = mockk<NewznabClient>()
        val service = BuiltInUsenetService(client, mockk<TmdbService>(), mockk<TmdbApi>())
        assertTrue(service.search(UsenetSourceConfiguration(), "movie", "tt123", null, null).toList().isEmpty())
        coVerify(exactly = 0) { client.capabilities(any()) }
    }
}
