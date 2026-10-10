package com.nuvio.tv.core.usenet

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NewznabClientTest {
    private class MemoryStorage(var value: String? = null) : NewznabStateStorage {
        var saves = 0
        override fun load() = value
        override fun save(value: String) { this.value = value; saves++ }
    }

    private val capsXml = """<caps><limits max="1"/><searching><movie-search available="yes" supportedParams="imdbid"/></searching></caps>"""
    private fun MockWebServer.page(title: String, id: Int, total: Int = 1) =
        """<rss xmlns:n="urn:newznab"><channel><n:response total="$total"/><item><title>$title</title><enclosure type="application/x-nzb" url="${url("/get?id=$id")}" length="100"/></item></channel></rss>"""
    private fun MockWebServer.indexer(key: String = "secret") =
        UsenetIndexer(name = "Test", apiUrl = url("/1/api?custom=1").toString(), apiKey = key)

    @Test fun `caps are cached and bounded pagination retains complete endpoint parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1, total = 4)))
            server.enqueue(MockResponse().setBody(server.page("Movie.2160p", 2, total = 4)))
            val indexer = server.indexer()
            val client = NewznabClient(MemoryStorage())
            val caps = client.capabilities(indexer)
            assertEquals(caps, client.capabilities(indexer))
            val releases = client.search(indexer, UsenetSearchRequest(imdbId = "tt123"), caps)
            assertEquals(2, releases.size)
            assertEquals(3, server.requestCount)
            assertEquals("caps", server.takeRequest().requestUrl!!.queryParameter("t"))
            val first = server.takeRequest().requestUrl!!
            assertEquals("/1/api", first.encodedPath)
            assertEquals("1", first.queryParameter("custom"))
            assertEquals("0", first.queryParameter("offset"))
            assertEquals("1", server.takeRequest().requestUrl!!.queryParameter("offset"))
        }
    }

    @Test fun `caps survive a restart, refetch for an edited key and fall back when expired and unreachable`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            server.enqueue(MockResponse().setBody(capsXml))
            val caps = NewznabClient(storage).capabilities(server.indexer())
            assertFalse(storage.value!!.contains("secret"))
            // A new client stands in for an app restart.
            assertEquals(caps, NewznabClient(storage).capabilities(server.indexer()))
            assertEquals(1, server.requestCount)

            server.enqueue(MockResponse().setBody(capsXml))
            NewznabClient(storage).capabilities(server.indexer(key = "edited"))
            assertEquals(2, server.requestCount)

            val eightDaysAgo = System.currentTimeMillis() - 8L * 24 * 3600 * 1000
            NewznabIndexerStates(storage) { eightDaysAgo }.update(server.indexer()) { it.copy(capsFetchedAt = eightDaysAgo) }
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(caps, NewznabClient(storage).capabilities(server.indexer()))
            assertEquals(3, server.requestCount)
        }
    }

    @Test fun `throttled indexer is skipped without requests until retry after, even across a restart`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            val client = NewznabClient(storage)
            val indexer = server.indexer()
            val caps = NewznabCapabilities()
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            val first = assertThrows(IndexerCooldownException::class.java) {
                runBlocking { client.search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertTrue(first.until - System.currentTimeMillis() in 100_000L..120_000L)
            assertThrows(IndexerCooldownException::class.java) {
                runBlocking { NewznabClient(storage).search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertEquals(1, server.requestCount)
            // Testing an indexer is an explicit request and ignores the cooldown.
            server.enqueue(MockResponse().setBody(capsXml))
            client.capabilities(indexer, live = true)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun `one season search serves following episodes and missing episodes are searched alone`() = runBlocking {
        MockWebServer().use { server ->
            fun items(vararg titles: String) = titles.mapIndexed { i, title ->
                """<item><title>$title</title><enclosure type="application/x-nzb" url="${server.url("/get?id=$title$i")}" length="100"/></item>"""
            }.joinToString("")
            server.enqueue(MockResponse().setBody("""<rss><channel>${items("Show.S01E01.1080p", "Show.S01E02.1080p", "a8f3k2b9")}</channel></rss>"""))
            server.enqueue(MockResponse().setBody("""<rss><channel>${items("Show.S01E03.1080p")}</channel></rss>"""))
            val client = NewznabClient(MemoryStorage())
            val indexer = server.indexer()
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 1, episode = n)
            val caps = NewznabCapabilities()

            // Season results need explicit numbering: an obfuscated name could be any episode.
            assertEquals(listOf("Show.S01E01.1080p"), client.search(indexer, episode(1), caps).map { it.title })
            assertEquals(listOf("Show.S01E02.1080p"), client.search(indexer, episode(2), caps).map { it.title })
            assertEquals(1, server.requestCount)
            val season = server.takeRequest().requestUrl!!
            assertEquals("1", season.queryParameter("season"))
            assertNull(season.queryParameter("ep"))

            // Episode 3 is not in the cached season, so it is searched alone.
            assertEquals(listOf("Show.S01E03.1080p"), client.search(indexer, episode(3), caps).map { it.title })
            assertEquals(2, server.requestCount)
            assertEquals("3", server.takeRequest().requestUrl!!.queryParameter("ep"))
        }
    }

    @Test fun `season search falls back to the episode when the indexer rejects it`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(400))
            server.enqueue(MockResponse().setBody(server.page("Show.S02E05.1080p", 1)))
            server.enqueue(MockResponse().setBody(server.page("Show.S02E06.1080p", 2)))
            val client = NewznabClient(MemoryStorage())
            fun episode(n: Int) = UsenetSearchRequest(imdbId = "tt1", series = true, season = 2, episode = n)
            assertEquals(listOf("Show.S02E05.1080p"), client.search(server.indexer(), episode(5), NewznabCapabilities()).map { it.title })
            assertEquals(listOf("Show.S02E06.1080p"), client.search(server.indexer(), episode(6), NewznabCapabilities()).map { it.title })
            // The rejected season search is not retried for the next episode.
            assertEquals(3, server.requestCount)
            assertNull(server.takeRequest().requestUrl!!.queryParameter("ep"))
            assertEquals("5", server.takeRequest().requestUrl!!.queryParameter("ep"))
            assertEquals("6", server.takeRequest().requestUrl!!.queryParameter("ep"))
        }
    }

    @Test fun `status records the last failure by category until a request succeeds`() = runBlocking {
        MockWebServer().use { server ->
            val storage = MemoryStorage()
            val client = NewznabClient(storage)
            val indexer = server.indexer()
            val caps = NewznabCapabilities()
            suspend fun search() = runCatching { client.search(indexer, UsenetSearchRequest(imdbId = "tt1"), caps) }
            assertEquals(IndexerStatus(), client.status(indexer))

            server.enqueue(MockResponse().setBody("""<error code="100" description="Incorrect user credentials"/>"""))
            search()
            assertEquals(IndexerProblem.AUTH, client.status(indexer).problem)
            server.enqueue(MockResponse().setResponseCode(403))
            search()
            assertEquals(IndexerProblem.AUTH, client.status(indexer).problem)
            server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))
            search()
            assertEquals(IndexerProblem.RESPONSE, client.status(indexer).problem)

            val revision = client.statusChanges.value
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)))
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)))
            search(); val saves = storage.saves; search()
            assertNull(client.status(indexer).problem)
            assertTrue(client.statusChanges.value > revision)
            assertEquals("an unchanged outcome must not rewrite the store", saves, storage.saves)
            // The status survives a restart.
            server.enqueue(MockResponse().setBody("""<error code="101" description="Account suspended"/>"""))
            search()
            assertEquals(IndexerProblem.AUTH, NewznabClient(storage).status(indexer).problem)
        }
        val closed = MockWebServer().apply { start() }
        val gone = closed.indexer()
        closed.shutdown()
        val client = NewznabClient(MemoryStorage())
        runCatching { client.search(gone, UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities()) }
        assertEquals(IndexerProblem.UNREACHABLE, client.status(gone).problem)
    }

    @Test fun `a pause shows in the status`() = runBlocking {
        MockWebServer().use { server ->
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "120"))
            runCatching { client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), NewznabCapabilities()) }
            assertTrue(client.status(server.indexer()).pausedUntil > System.currentTimeMillis())
        }
    }

    @Test fun `verifying an indexer checks the API key, not only the public caps`() = runBlocking {
        MockWebServer().use { server ->
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody("""<error code="100" description="Incorrect user credentials"/>"""))
            assertThrows(NewznabAuthException::class.java) { runBlocking { client.verify(server.indexer(key = "wrong")) } }
            assertEquals("caps", server.takeRequest().requestUrl!!.queryParameter("t"))
            val search = server.takeRequest().requestUrl!!
            assertEquals("search", search.queryParameter("t"))
            assertEquals("1", search.queryParameter("limit"))
            assertEquals("wrong", search.queryParameter("apikey"))
            assertEquals(IndexerProblem.AUTH, client.status(server.indexer(key = "wrong")).problem)

            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody(server.page("Latest.Release", 1)))
            client.verify(server.indexer())
            // A search refused for its parameters still accepted the key.
            server.enqueue(MockResponse().setBody(capsXml))
            server.enqueue(MockResponse().setBody("""<error code="201" description="Incorrect parameter"/>"""))
            client.verify(server.indexer())
            assertEquals(6, server.requestCount)
        }
    }

    @Test fun `spent quota from an error document or headers pauses the indexer`() = runBlocking {
        MockWebServer().use { server ->
            val caps = NewznabCapabilities()
            server.enqueue(MockResponse().setBody("""<error code="500" description="Request limit reached"/>"""))
            val quota = assertThrows(IndexerCooldownException::class.java) {
                runBlocking { NewznabClient(MemoryStorage()).search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertTrue(quota.until - System.currentTimeMillis() > 25 * 60_000L)

            // The last allowed request still returns its results; the next one is skipped.
            val client = NewznabClient(MemoryStorage())
            server.enqueue(MockResponse().setBody(server.page("Movie.1080p", 1)).setHeader("x-api-remaining", "0"))
            assertEquals(1, client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps).size)
            assertThrows(IndexerCooldownException::class.java) {
                runBlocking { client.search(server.indexer(), UsenetSearchRequest(imdbId = "tt1"), caps) }
            }
            assertEquals(2, server.requestCount)
        }
    }
}
