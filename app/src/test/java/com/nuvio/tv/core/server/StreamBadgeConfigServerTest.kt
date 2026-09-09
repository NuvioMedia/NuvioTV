package com.nuvio.tv.core.server

import com.google.gson.Gson
import com.nuvio.tv.core.streams.StreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeImport
import com.nuvio.tv.core.streams.StreamBadgePlacement
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.core.streams.StreamBadgeSettings
import fi.iki.elonen.NanoHTTPD
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.charset.StandardCharsets

class StreamBadgeConfigServerTest {
    @Test
    fun `saves imported badge rules from pasted fusion json`() {
        var settings = StreamBadgeSettings()
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { settings },
            onSettingsChanged = { settings = it }
        )
        val body = Gson().toJson(
            mapOf(
                "sourceUrl" to "https://example.com/fusion-badges.json",
                "payload" to """
                    {
                      "filters": [
                        {
                          "name": "Dolby Vision",
                          "pattern": "DV",
                          "imageURL": "https://cdn.example/dv.png"
                        }
                      ],
                      "groups": []
                    }
                """.trimIndent()
            )
        )

        val response = server.serve(FakePostSession(body, "/api/badges/import"))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, settings.rules.imports.size)
        assertEquals("https://example.com/fusion-badges.json", settings.rules.imports.first().sourceUrl)
        assertEquals("Dolby Vision", settings.rules.imports.first().filters.first().name)
    }

    @Test
    fun `saves badge rules and file size setting through settings payload`() {
        var saved: StreamBadgeSettings? = null
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/badges.json",
                    filters = listOf(StreamBadgeFilter(name = "Atmos", pattern = "Atmos"))
                )
            )
        )
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { StreamBadgeSettings() },
            onSettingsChanged = { saved = it }
        )
        val body = Gson().toJson(
            mapOf(
                "streamBadgeRules" to rules,
                "showFileSizeBadges" to false,
                "badgePlacement" to "TOP"
            )
        )

        val response = server.serve(FakePostSession(body))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(1, saved?.rules?.imports?.size)
        assertEquals("Atmos", saved?.rules?.imports?.first()?.filters?.first()?.name)
        assertEquals(false, saved?.showFileSizeBadges)
        assertEquals(StreamBadgePlacement.TOP, saved?.badgePlacement)
    }

    @Test
    fun `normalizes badge rules with a selected source through settings payload`() {
        var saved: StreamBadgeSettings? = null
        val rules = StreamBadgeRules(
            imports = listOf(
                StreamBadgeImport(
                    sourceUrl = "https://example.com/one.json",
                    isActive = false,
                    filters = listOf(StreamBadgeFilter(name = "One", pattern = "One"))
                ),
                StreamBadgeImport(
                    sourceUrl = "https://example.com/two.json",
                    isActive = false,
                    filters = listOf(StreamBadgeFilter(name = "Two", pattern = "Two"))
                )
            )
        )
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { StreamBadgeSettings(badgePlacement = StreamBadgePlacement.TOP) },
            onSettingsChanged = { saved = it }
        )
        val body = Gson().toJson(mapOf("streamBadgeRules" to rules))

        val response = server.serve(FakePostSession(body))

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertEquals(listOf(true, false), saved?.rules?.imports?.map { it.isActive })
        assertEquals(StreamBadgePlacement.TOP, saved?.badgePlacement)
    }

    @Test
    fun `serves explicit active state for badge web client`() {
        val settings = StreamBadgeSettings(
            rules = StreamBadgeRules(
                imports = listOf(
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/one.json",
                        isActive = true,
                        filters = listOf(StreamBadgeFilter(name = "One", pattern = "One"))
                    ),
                    StreamBadgeImport(
                        sourceUrl = "https://example.com/two.json",
                        isActive = false,
                        filters = listOf(StreamBadgeFilter(name = "Two", pattern = "Two"))
                    )
                )
            ),
            badgePlacement = StreamBadgePlacement.TOP
        )
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { settings },
            onSettingsChanged = {}
        )

        val response = server.serve(FakeGetSession())
        val body = response.data.bufferedReader(StandardCharsets.UTF_8).use { it.readText() }

        assertEquals(NanoHTTPD.Response.Status.OK, response.status)
        assertTrue(body.contains("\"isActive\":true"))
        assertTrue(body.contains("\"isActive\":false"))
        assertTrue(body.contains("\"badgePlacement\":\"TOP\""))
    }

    @Test
    fun `rejects badge import from a loopback address`() {
        assertBadgeImportRejected("http://127.0.0.1/badges.json")
    }

    @Test
    fun `rejects badge import from localhost hostname`() {
        assertBadgeImportRejected("http://localhost/badges.json")
    }

    @Test
    fun `rejects badge import from the wildcard address`() {
        assertBadgeImportRejected("http://0.0.0.0/badges.json")
    }

    @Test
    fun `rejects badge import from a 10-8 private address`() {
        assertBadgeImportRejected("http://10.0.0.1/badges.json")
    }

    @Test
    fun `rejects badge import from a 172-16-12 private address`() {
        assertBadgeImportRejected("http://172.16.0.1/badges.json")
    }

    @Test
    fun `rejects badge import from a 192-168-16 private address`() {
        assertBadgeImportRejected("http://192.168.1.1/badges.json")
    }

    @Test
    fun `rejects badge import from a link-local address`() {
        assertBadgeImportRejected("http://169.254.1.1/badges.json")
    }

    @Test
    fun `rejects badge import from IPv6 loopback`() {
        assertBadgeImportRejected("http://[::1]/badges.json")
    }

    @Test
    fun `rejects badge import from an IPv6 unique-local address`() {
        assertBadgeImportRejected("http://[fd12:3456:789a::1]/badges.json")
    }

    @Test
    fun `rejects a literal loopback IP sourceUrl even though a real server answers there`() {
        // This is the regression test for the actual gap found while writing this suite:
        // OkHttp skips Dns.lookup() entirely when a URL's host is already a literal IP
        // address, so SsrfProtectedDns alone never saw this request - a bare
        // assertBadgeImportRejected("http://127.0.0.1/...") case passed only because
        // nothing was listening on port 80 there, not because anything blocked it. This
        // test uses a *real, responding* server on loopback to prove the request is
        // rejected because of the check, not a coincidental connection failure - and
        // that fix (a network interceptor checking the address OkHttp actually connected
        // to, reusing the same isDisallowedForSsrf() check SsrfProtectedDns uses) is also
        // what closes the identical gap for a redirect Location header that is itself a
        // literal private IP: OkHttp's network interceptors run once per real network
        // exchange, which includes every redirect hop, not just the first one.
        val jsonBody = """{"filters":[{"name":"Dolby Vision","pattern":"DV","imageURL":"https://cdn.example/dv.png"}],"groups":[]}"""
        FakeHttpOriginServer(
            "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: ${jsonBody.toByteArray(StandardCharsets.UTF_8).size}\r\n" +
                "Connection: close\r\n\r\n" +
                jsonBody
        ).use { origin ->
            var settings = StreamBadgeSettings()
            val badgeServer = StreamBadgeConfigServer(
                currentSettingsProvider = { settings },
                onSettingsChanged = { settings = it }
            )
            val body = Gson().toJson(
                mapOf("sourceUrl" to "http://${InetAddress.getLoopbackAddress().hostAddress}:${origin.port}/badges.json")
            )

            val response = badgeServer.serve(FakePostSession(body, "/api/badges/import"))

            assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
            assertEquals(0, settings.rules.imports.size)
        }
    }

    /**
     * A minimal single-shot HTTP server on loopback that writes [rawResponse] verbatim
     * after draining the request headers - just enough for OkHttp to parse a response,
     * without pulling in a full HTTP server test dependency.
     */
    private class FakeHttpOriginServer(rawResponse: String) : AutoCloseable {
        private val serverSocket = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val port: Int get() = serverSocket.localPort
        private val thread = Thread {
            try {
                serverSocket.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(StandardCharsets.ISO_8859_1)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().write(rawResponse.toByteArray(StandardCharsets.ISO_8859_1))
                    socket.getOutputStream().flush()
                }
            } catch (_: Exception) {
                // Socket closed from close() while waiting for a connection - nothing to
                // assert on here, the test itself will fail if it never got a response.
            }
        }.apply { isDaemon = true; start() }

        override fun close() {
            runCatching { serverSocket.close() }
            thread.join(2_000)
        }
    }

    private fun assertBadgeImportRejected(sourceUrl: String) {
        var settings = StreamBadgeSettings()
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { settings },
            onSettingsChanged = { settings = it }
        )
        val body = Gson().toJson(mapOf("sourceUrl" to sourceUrl))

        val response = server.serve(FakePostSession(body, "/api/badges/import"))

        assertEquals(NanoHTTPD.Response.Status.BAD_REQUEST, response.status)
        assertEquals(0, settings.rules.imports.size)
    }

    private class FakePostSession(
        body: String,
        private val uri: String = "/api/settings"
    ) : NanoHTTPD.IHTTPSession {
        private val bytes = body.toByteArray(StandardCharsets.UTF_8)

        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = mapOf(
            "content-length" to bytes.size.toString(),
            "content-type" to "application/json; charset=utf-8"
        )
        override fun getInputStream(): InputStream = ByteArrayInputStream(bytes)
        override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.POST
        @Deprecated("Deprecated in NanoHTTPD")
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uri
        @Deprecated("Deprecated in NanoHTTPD")
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }

    private class FakeGetSession(
        private val uri: String = "/api/settings"
    ) : NanoHTTPD.IHTTPSession {
        override fun execute() = Unit
        override fun getCookies(): NanoHTTPD.CookieHandler? = null
        override fun getHeaders(): Map<String, String> = emptyMap()
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getMethod(): NanoHTTPD.Method = NanoHTTPD.Method.GET
        @Deprecated("Deprecated in NanoHTTPD")
        override fun getParms(): Map<String, String> = emptyMap()
        override fun getParameters(): Map<String, List<String>> = emptyMap()
        override fun getQueryParameterString(): String? = null
        override fun getUri(): String = uri
        @Deprecated("Deprecated in NanoHTTPD")
        override fun parseBody(files: MutableMap<String, String>) {
            error("parseBody should not be used")
        }
        override fun getRemoteIpAddress(): String = "127.0.0.1"
        override fun getRemoteHostName(): String = "localhost"
    }
}
