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
