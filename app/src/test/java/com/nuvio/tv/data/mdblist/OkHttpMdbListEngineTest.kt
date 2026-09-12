package com.nuvio.tv.data.mdblist

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class OkHttpMdbListEngineTest {
    @Test
    fun `list update and deletion use the correct methods and bearer authentication`() = runTest {
        MockWebServer().use { server ->
            for (method in listOf(MdbListHttpMethod.PUT, MdbListHttpMethod.DELETE)) {
                server.enqueue(MockResponse().setResponseCode(204))
                val response = engine(server).execute(MdbListHttpRequest(
                    method, "/lists/42", body = """{"name":"Weekend","private":true}""", accessToken = "access-token"
                ))
                val sent = server.takeRequest()
                assertEquals(method.name, sent.method)
                assertEquals("/lists/42", sent.path)
                assertEquals("Bearer access-token", sent.getHeader("Authorization"))
                assertEquals(204, response.status)
                if (method == MdbListHttpMethod.PUT) {
                    assertEquals("""{"name":"Weekend","private":true}""", sent.body.readUtf8())
                    assertTrue(sent.getHeader("Content-Type").orEmpty().startsWith("application/json"))
                } else assertEquals(0L, sent.bodySize)
            }
        }
    }

    @Test
    fun `OAuth form escaping and trailing slash reach the server unchanged`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            val engine = engine(server)
            engine.execute(MdbListHttpRequest(
                MdbListHttpMethod.POST, "/oauth/token/", form = mapOf("client_id" to "public", "device_code" to "a+b&c")
            ))

            val sent = server.takeRequest()
            assertEquals("/oauth/token/", sent.path)
            assertEquals("client_id=public&device_code=a%2Bb%26c", sent.body.readUtf8())
            assertTrue(sent.getHeader("Content-Type").orEmpty().startsWith("application/x-www-form-urlencoded"))
            assertEquals(null, sent.getHeader("Authorization"))
        }
    }

    @Test
    fun `bearer authentication stays out of URL and cursor values are encoded`() = runTest {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("{}"))
            engine(server).execute(MdbListHttpRequest(
                MdbListHttpMethod.GET, "/sync/journal", query = mapOf("cursor" to "a+b/c="), accessToken = "access-token"
            ))

            val sent = server.takeRequest()
            assertEquals("Bearer access-token", sent.getHeader("Authorization"))
            assertEquals("a+b/c=", sent.requestUrl?.queryParameter("cursor"))
            assertFalse(sent.path.orEmpty().contains("access-token"))
        }
    }

    @Test
    fun `redirects never forward credentials to another destination`() = runTest {
        MockWebServer().use { server ->
            MockWebServer().use { destination ->
                server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", destination.url("/stolen")))

                val response = engine(server).execute(MdbListHttpRequest(
                    MdbListHttpMethod.GET, "/user", accessToken = "access-token"
                ))

                assertEquals(302, response.status)
                assertEquals(0, destination.requestCount)
            }
        }
    }

    @Test
    fun `coroutine cancellation cancels in flight HTTP call`() = runTest {
        MockWebServer().use { server ->
            val client = OkHttpClient()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val engine = engine(server, client)
            val operation = async(Dispatchers.IO) { engine.execute(MdbListHttpRequest(MdbListHttpMethod.GET, "/user")) }
            val request = withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) }
            assertNotNull(request)

            operation.cancel()
            operation.join()

            assertTrue(operation.isCancelled)
            assertTrue(client.dispatcher.runningCalls().all { it.isCanceled() })
        }
    }

    @Test
    fun `response size is bounded even when the server omits content length`() {
        val declared = Buffer().writeUtf8("unread")
        assertThrows(IOException::class.java) { readMdbListResponseBody(declared, 1_025, 1_024) }
        assertEquals("unread", declared.readUtf8())
        assertThrows(IOException::class.java) {
            readMdbListResponseBody(Buffer().write(ByteArray(1_025)), -1, 1_024)
        }
        assertEquals("valid", readMdbListResponseBody(Buffer().writeUtf8("valid"), -1, 1_024))
    }

    @Test
    fun `production credentials cannot be sent over plaintext HTTP`() {
        val engine = OkHttpMdbListEngine(OkHttpClient(), MdbListConfiguration("client", "test", "http://api.mdblist.com"))

        assertThrows(IllegalArgumentException::class.java) {
            engine.buildRequest(MdbListHttpRequest(MdbListHttpMethod.GET, "/user", accessToken = "token"))
        }
    }

    private fun engine(server: MockWebServer, client: OkHttpClient = OkHttpClient()) =
        OkHttpMdbListEngine(client, MdbListConfiguration("client", "test", server.url("/").toString()))
}
