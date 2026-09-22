package com.nuvio.tv.ui.screens.player

import okhttp3.Dns
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.security.KeyStore
import java.util.Collections
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import kotlin.concurrent.thread

class SubtitleCredentialScopeTest {

    private val streamHeaders = mapOf(
        "Authorization" to "Bearer token",
        "Cookie" to "session=1",
        "X-Api-Key" to "secret",
        "Referer" to "https://addon.example/",
        "Origin" to "https://addon.example",
        "User-Agent" to "Player",
        "Accept-Language" to "en",
        "Range" to "bytes=0-"
    )

    private val crossHostHeaders = mapOf(
        "Referer" to "https://addon.example/",
        "Origin" to "https://addon.example",
        "User-Agent" to "Player",
        "Accept-Language" to "en"
    )

    @Test
    fun `the scope is the stream's own host, without a downgrade to http`() {
        val stream = "https://video.example.com/v.mkv".toHttpUrl()

        assertTrue(isInHeaderScope("https://video.example.com/a.srt".toHttpUrl(), stream))
        assertTrue(isInHeaderScope("https://VIDEO.example.com/a.srt".toHttpUrl(), stream))
        assertFalse(isInHeaderScope("http://video.example.com/a.srt".toHttpUrl(), stream))
        assertTrue(
            isInHeaderScope(
                "http://video.example.com/a.srt".toHttpUrl(),
                "http://video.example.com/v.mkv".toHttpUrl()
            )
        )
        // A sibling host under the same domain is outside.
        assertFalse(isInHeaderScope("https://subs.example.com/a.srt".toHttpUrl(), stream))
        assertFalse(isInHeaderScope("https://example.com/a.srt".toHttpUrl(), stream))
        assertFalse(isInHeaderScope("https://video.example.com/a.srt".toHttpUrl(), null))
        // Matched by the old last-two-label check.
        assertFalse(
            isInHeaderScope(
                "https://evil.example.co.uk/a.srt".toHttpUrl(),
                "https://video.example.co.uk/v.mkv".toHttpUrl()
            )
        )
        assertFalse(
            isInHeaderScope(
                "https://attacker.github.io/a.srt".toHttpUrl(),
                "https://victim.github.io/v.mkv".toHttpUrl()
            )
        )
    }

    @Test
    fun `a subtitle on the stream's host receives every stream header but request control ones`() {
        val request = buildSubtitleRequest(
            "https://video.example.com/a.srt".toHttpUrl(),
            "https://video.example.com/v.mkv".toHttpUrl(),
            streamHeaders,
            explicitHeaders = null
        )

        (streamHeaders - "Range").forEach { (name, value) -> assertEquals(name, value, request.header(name)) }
        assertNull(request.header("Range"))
    }

    @Test
    fun `a subtitle on another host receives only the cross host headers`() {
        val request = buildSubtitleRequest(
            "https://subs.example.com/a.srt".toHttpUrl(),
            "https://video.example.com/v.mkv".toHttpUrl(),
            streamHeaders,
            explicitHeaders = null
        )

        // Custom credential names are dropped too.
        crossHostHeaders.forEach { (name, value) -> assertEquals(name, value, request.header(name)) }
        assertNull(request.header("Authorization"))
        assertNull(request.header("Cookie"))
        assertNull(request.header("X-Api-Key"))

        // HTTPS stream, HTTP subtitle: no Referer or Origin either.
        val downgraded = buildSubtitleRequest(
            "http://subs.example.com/a.srt".toHttpUrl(),
            "https://video.example.com/v.mkv".toHttpUrl(),
            streamHeaders,
            explicitHeaders = null
        )
        assertNull(downgraded.header("Referer"))
        assertNull(downgraded.header("Origin"))
        assertEquals("Player", downgraded.header("User-Agent"))
        assertNull(downgraded.header("X-Api-Key"))

        // No stream URL: nothing in scope, subtitle headers still sent.
        val noStream = buildSubtitleRequest(
            "https://subs.example.com/a.srt".toHttpUrl(),
            streamUrl = null,
            streamHeaders = streamHeaders,
            explicitHeaders = mapOf("X-Subtitle-Key" to "own")
        )
        assertEquals("own", noStream.header("X-Subtitle-Key"))
        assertNull(noStream.header("X-Api-Key"))
    }

    @Test
    fun `stream and subtitle headers are scoped separately`() {
        val request = buildSubtitleRequest(
            "https://video.example.com/a.srt".toHttpUrl(),
            "https://video.example.com/v.mkv".toHttpUrl(),
            streamHeaders,
            explicitHeaders = mapOf("x-api-key" to "subtitle-own", "Referer" to "https://subs.example/")
        )
        // Subtitle header wins, even with different casing, and is tracked as the subtitle's.
        assertEquals("subtitle-own", request.header("X-Api-Key"))
        assertEquals(setOf("x-api-key"), request.tag(SubtitleOwnHeaders::class.java)?.names)
        assertTrue(request.tag(ForwardedStreamHeaders::class.java)?.names.orEmpty().none { it.lowercase() == "x-api-key" })

        // The TLS retry drops both sets, but keeps cross-host headers.
        val retry = request.withoutCredentialHeaders()
        assertNull(retry.header("X-Api-Key"))
        assertNull(retry.header("Authorization"))
        assertEquals("https://subs.example/", retry.header("Referer"))
        assertEquals("Player", retry.header("User-Agent"))
    }

    @Test
    fun `a redirect to another host drops stream and subtitle credentials on that hop`() {
        // Same server, different hosts.
        HeaderRecordingServer().use { server ->
            val base = "http://127.0.0.1:${server.port}"
            server.redirects["/redirect.srt"] = "http://localhost:${server.port}/landed.srt"
            val request = buildSubtitleRequest(
                "$base/redirect.srt".toHttpUrl(),
                "$base/v.mkv".toHttpUrl(),
                streamHeaders,
                explicitHeaders = mapOf("X-Subtitle-Key" to "own")
            )

            subtitleHttpClient.newCall(request).execute().use { assertEquals(200, it.code) }

            server.assertEvery("/redirect.srt") {
                assertEquals("secret", it["x-api-key"])
                assertEquals("session=1", it["cookie"])
                assertEquals("own", it["x-subtitle-key"])
            }
            server.assertEvery("/landed.srt") {
                assertNull(it["x-api-key"])
                assertNull(it["cookie"])
                assertNull(it["authorization"])
                assertNull(it["x-subtitle-key"])
                assertEquals("https://addon.example/", it["referer"])
                assertEquals("https://addon.example", it["origin"])
            }
        }
    }

    @Test
    fun `a redirect chain through http on the stream's host drops the headers only on the http hop`() {
        val tls = testTls()
        HeaderRecordingServer(tls).use { httpsServer ->
            HeaderRecordingServer().use { httpServer ->
                val httpsBase = "https://127.0.0.1:${httpsServer.port}"
                httpsServer.redirects["/redirect.srt"] = "http://127.0.0.1:${httpServer.port}/middle.srt"
                httpServer.redirects["/middle.srt"] = "$httpsBase/landed.srt"
                val request = buildSubtitleRequest(
                    "$httpsBase/redirect.srt".toHttpUrl(),
                    "$httpsBase/v.mkv".toHttpUrl(),
                    streamHeaders,
                    explicitHeaders = mapOf("X-Subtitle-Key" to "own", "Referer" to "https://subs.example/")
                )
                // Real subtitle client, trusting the test certificate.
                val client = subtitleHttpClient.newBuilder()
                    .sslSocketFactory(tls.socketFactory, tls.trustManager)
                    .build()

                client.newCall(request).execute().use { assertEquals(200, it.code) }

                httpsServer.assertEvery("/redirect.srt") {
                    assertEquals("secret", it["x-api-key"])
                    assertEquals("session=1", it["cookie"])
                    assertEquals("own", it["x-subtitle-key"])
                    assertEquals("https://subs.example/", it["referer"])
                }
                httpServer.assertEvery("/middle.srt") {
                    assertNull(it["x-api-key"])
                    assertNull(it["cookie"])
                    // Downgraded hop drops Referer and Origin too, the subtitle's own included.
                    assertNull(it["x-subtitle-key"])
                    assertNull(it["referer"])
                    assertNull(it["origin"])
                    assertEquals("Player", it["user-agent"])
                    assertEquals("en", it["accept-language"])
                }
                // Back on the stream's HTTPS host, so they are allowed again. Authorization isn't: OkHttp
                // drops it after a scheme change.
                httpsServer.assertEvery("/landed.srt") {
                    assertEquals("secret", it["x-api-key"])
                    assertEquals("session=1", it["cookie"])
                    assertEquals("own", it["x-subtitle-key"])
                    assertEquals("https://subs.example/", it["referer"])
                    assertNull(it["authorization"])
                }
            }
        }
    }

    @Test
    fun `a redirect within the same https host keeps stream and subtitle credentials`() {
        val tls = testTls()
        HeaderRecordingServer(tls).use { server ->
            val base = "https://127.0.0.1:${server.port}"
            server.redirects["/redirect.srt"] = "$base/landed.srt"
            val request = buildSubtitleRequest(
                "$base/redirect.srt".toHttpUrl(),
                "$base/v.mkv".toHttpUrl(),
                streamHeaders,
                explicitHeaders = mapOf("X-Subtitle-Key" to "own")
            )
            val client = subtitleHttpClient.newBuilder()
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .build()

            client.newCall(request).execute().use { assertEquals(200, it.code) }

            listOf("/redirect.srt", "/landed.srt").forEach { path ->
                server.assertEvery(path) {
                    assertEquals("secret", it["x-api-key"])
                    assertEquals("session=1", it["cookie"])
                    assertEquals("Bearer token", it["authorization"])
                    assertEquals("own", it["x-subtitle-key"])
                }
            }
        }
    }

    @Test
    fun `a redirect to a host failing hostname verification is retried without credentials`() {
        val tls = testTls()
        // Cert only covers 127.0.0.1, so localhost fails verification. DNS is pinned to IPv4, otherwise
        // OkHttp reports the refused ::1 route instead.
        val loopbackOnly = object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = listOf(InetAddress.getByName("127.0.0.1"))
        }
        HeaderRecordingServer(tls).use { server ->
            val base = "https://127.0.0.1:${server.port}"
            server.redirects["/redirect.srt"] = "https://localhost:${server.port}/landed.srt"
            val request = buildSubtitleRequest(
                "$base/redirect.srt".toHttpUrl(),
                "$base/v.mkv".toHttpUrl(),
                streamHeaders,
                explicitHeaders = mapOf("X-Subtitle-Key" to "own")
            )
            // No connection retries, so request counts are exact.
            val validated = subtitleHttpClient.newBuilder()
                .dns(loopbackOnly)
                .retryOnConnectionFailure(false)
                .sslSocketFactory(tls.socketFactory, tls.trustManager)
                .build()
            val permissive = subtitleUnvalidatedTlsHttpClient.newBuilder()
                .dns(loopbackOnly)
                .retryOnConnectionFailure(false)
                .build()

            executeSubtitleRequest(request, validated, permissive).use { assertEquals(200, it.code) }

            // Validated attempt, with credentials.
            val redirectHops = server.requestsTo("/redirect.srt")
            assertEquals(2, redirectHops.size)
            assertEquals("secret", redirectHops[0]["x-api-key"])
            assertEquals("own", redirectHops[0]["x-subtitle-key"])
            // Retry, without them.
            assertNull(redirectHops[1]["x-api-key"])
            assertNull(redirectHops[1]["cookie"])
            assertNull(redirectHops[1]["x-subtitle-key"])
            val landed = server.requestsTo("/landed.srt").single()
            assertNull(landed["x-api-key"])
            assertNull(landed["cookie"])
            assertNull(landed["authorization"])
            assertNull(landed["x-subtitle-key"])
            assertEquals("https://addon.example/", landed["referer"])
        }
    }

    private class TestTls(val serverContext: SSLContext, val trustManager: X509TrustManager) {
        val socketFactory = SSLContext.getInstance("TLS")
            .apply { init(null, arrayOf(trustManager), null) }
            .socketFactory
    }

    /** Self-signed cert for 127.0.0.1, valid until 2126. */
    private fun testTls(): TestTls {
        val password = "changeit".toCharArray()
        val resource = requireNotNull(
            SubtitleCredentialScopeTest::class.java.getResourceAsStream("/subtitle-redirect-test.p12")
        ) { "subtitle-redirect-test.p12 missing from test resources" }
        val keyStore = KeyStore.getInstance("PKCS12").apply { resource.use { load(it, password) } }
        val keyManagers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore, password) }.keyManagers
        val trustManager = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }.trustManagers.filterIsInstance<X509TrustManager>().first()
        val serverContext = SSLContext.getInstance("TLS").apply { init(keyManagers, null, null) }
        return TestTls(serverContext, trustManager)
    }

    private class RecordedRequest(val path: String, val headers: Map<String, String>)

    /** Records request headers; 302 for [redirects], a subtitle otherwise. */
    private class HeaderRecordingServer(tls: TestTls? = null) : AutoCloseable {
        private val socket: ServerSocket = tls?.serverContext?.serverSocketFactory
            ?.createServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
            ?: ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"))
        val port: Int = socket.localPort
        val redirects: MutableMap<String, String> = java.util.concurrent.ConcurrentHashMap()
        private val requests: MutableList<RecordedRequest> = Collections.synchronizedList(mutableListOf())

        private val acceptor = thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching {
                    client.use { connection ->
                        val reader = connection.getInputStream().bufferedReader()
                        val path = reader.readLine()?.split(' ')?.getOrNull(1) ?: return@use
                        val headers = generateSequence { reader.readLine() }
                            .takeWhile { it.isNotEmpty() }
                            .associate { line ->
                                line.substringBefore(':').trim().lowercase() to line.substringAfter(':').trim()
                            }
                        requests.add(RecordedRequest(path, headers))
                        val location = redirects[path]
                        val response = if (location != null) {
                            "HTTP/1.1 302 Found\r\nLocation: $location\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                        } else {
                            val body = "1\n00:00:01,000 --> 00:00:02,000\ntest\n"
                            "HTTP/1.1 200 OK\r\nContent-Type: application/x-subrip\r\n" +
                                "Content-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                        }
                        connection.getOutputStream().apply { write(response.toByteArray()); flush() }
                    }
                }
            }
        }

        fun requestsTo(path: String): List<Map<String, String>> =
            synchronized(requests) { requests.filter { it.path == path }.map { it.headers } }

        /** Runs [check] on every request to [path]; at least one is required. */
        fun assertEvery(path: String, check: (Map<String, String>) -> Unit) {
            val matching = synchronized(requests) { requests.filter { it.path == path } }
            assertTrue("no request to $path", matching.isNotEmpty())
            // OkHttp may retry, so check them all.
            matching.forEach { check(it.headers) }
        }

        override fun close() {
            socket.close()
            acceptor.join(1_000)
        }
    }
}
