package com.nuvio.tv.core.usenet

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

class NewznabClientTest {
    @Test fun `caps are cached and bounded pagination retains complete endpoint parameters`() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<caps><limits max="1"/><searching><movie-search available="yes" supportedParams="imdbid"/></searching></caps>"""))
            fun page(title: String, id: Int) = """<rss xmlns:n="urn:newznab"><channel><n:response total="4"/><item><title>$title</title><enclosure type="application/x-nzb" url="${server.url("/get?id=$id")}" length="100"/></item></channel></rss>"""
            server.enqueue(MockResponse().setBody(page("Movie.1080p", 1)))
            server.enqueue(MockResponse().setBody(page("Movie.2160p", 2)))
            val indexer = UsenetIndexer(name = "Test", apiUrl = server.url("/1/api?custom=1").toString(), apiKey = "secret")
            val client = NewznabClient()
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
}
