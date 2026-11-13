package com.nuvio.tv.data.floppy

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyApiClientTest {
    @Test
    fun `a valid token reads the connections feed and is reported connected`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"results":[]}"""))

            val result = client().checkConnection(credentials(server))

            val sent = server.takeRequest()
            assertEquals(FloppyConnectionResult.CONNECTED, result)
            assertEquals("GET", sent.method)
            assertEquals("/api/v1/sync/connections/", sent.path)
            assertEquals("Bearer flp_secret", sent.getHeader("Authorization"))
            assertEquals("NuvioTV/9.9.9", sent.getHeader("User-Agent"))
        }
    }

    @Test
    fun `a refused token is reported as rejected`() = runTest {
        MockWebServer().use { server ->
            for (status in listOf(401, 403)) {
                server.enqueue(MockResponse().setResponseCode(status).setBody("""{"detail":"nope"}"""))
                assertEquals(FloppyConnectionResult.REJECTED, client().checkConnection(credentials(server)))
            }
        }
    }

    @Test
    fun `a server that is not Floppy is not reported connected`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("not found"))
            server.enqueue(MockResponse().setBody("<html>login</html>"))
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "https://elsewhere.example/"))

            repeat(3) {
                assertEquals(FloppyConnectionResult.NOT_FLOPPY, client().checkConnection(credentials(server)))
            }
        }
    }

    @Test
    fun `an address nothing answers on is unreachable`() = runTest {
        val dead = MockWebServer().use { server -> credentials(server) }

        assertEquals(FloppyConnectionResult.UNREACHABLE, client().checkConnection(dead))
    }

    @Test
    fun `a scrobble is posted as json with the bearer token`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"detail":"accepted"}"""))
            val body = buildJsonObject {
                put("action", "start")
                put("media_type", "movie")
            }

            client().scrobble(credentials(server), body)

            val sent = server.takeRequest()
            assertEquals("POST", sent.method)
            assertEquals("/api/v1/scrobble/", sent.path)
            assertEquals("Bearer flp_secret", sent.getHeader("Authorization"))
            assertTrue(sent.getHeader("Content-Type").orEmpty().startsWith("application/json"))
            assertEquals("""{"action":"start","media_type":"movie"}""", sent.body.readUtf8())
        }
    }

    @Test
    fun `a reverse proxy path prefix is kept`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))

            client().scrobble(credentials(server, path = "/floppy"), buildJsonObject { put("action", "pause") })

            assertEquals("/floppy/api/v1/scrobble/", server.takeRequest().path)
        }
    }

    @Test
    fun `a refused scrobble throws with the status`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(404).setBody("""{"detail":"Could not resolve media."}"""))

            val error = assertThrows(FloppyApiException::class.java) {
                kotlinx.coroutines.runBlocking { client().scrobble(credentials(server), buildJsonObject { put("action", "stop") }) }
            }

            assertEquals(404, error.status)
        }
    }

    @Test
    fun `a redirect is not followed so the event is never replayed elsewhere`() = runTest {
        MockWebServer().use { target ->
            MockWebServer().use { server ->
                server.enqueue(MockResponse().setResponseCode(301).setHeader("Location", target.url("/api/v1/scrobble/").toString()))

                assertThrows(FloppyApiException::class.java) {
                    kotlinx.coroutines.runBlocking { client().scrobble(credentials(server), buildJsonObject { put("action", "stop") }) }
                }

                assertEquals(0, target.requestCount)
            }
        }
    }

    @Test
    fun `an oversized response is refused`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024)))

            assertEquals(FloppyConnectionResult.UNREACHABLE, client().checkConnection(credentials(server)))
        }
    }

    @Test
    fun `the token is never logged in the exception text`() {
        assertFalse(FloppyApiException(403).message.orEmpty().contains("flp_"))
    }

    private fun client() = FloppyApiClient(
        OkHttpClient.Builder().callTimeout(5, TimeUnit.SECONDS).build(),
        appVersion = "9.9.9"
    )

    private fun credentials(server: MockWebServer, path: String = "") =
        FloppyCredentials(server.url("/").toString().trimEnd('/') + path, "flp_secret")
}
