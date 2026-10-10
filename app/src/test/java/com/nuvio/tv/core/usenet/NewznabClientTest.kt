package com.nuvio.tv.core.usenet

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NewznabClientTest {
    private class MemoryStorage(var value: String? = null) : NewznabStateStorage {
        override fun load() = value
        override fun save(value: String) { this.value = value }
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
