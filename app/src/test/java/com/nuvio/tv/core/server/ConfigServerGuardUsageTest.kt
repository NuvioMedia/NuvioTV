package com.nuvio.tv.core.server

import android.content.Context
import com.nuvio.tv.core.streams.StreamBadgeSettings
import fi.iki.elonen.NanoHTTPD
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream

/**
 * Every config server must run the guard before its handlers, otherwise a page the user visits
 * could still change settings on the TV. These tests drive each `serve()` with a foreign Origin.
 */
class ConfigServerGuardUsageTest {

    private val context: Context = mockk(relaxed = true)

    @Test
    fun `addon server rejects a cross-origin post`() {
        var proposed = false
        val server = AddonConfigServer(
            context = context,
            webConfigMode = AddonWebConfigMode.FULL,
            currentPageStateProvider = { PageState(addons = emptyList(), catalogs = emptyList()) },
            onChangeProposed = { proposed = true },
            port = 0
        )

        assertRejected(server.serve(crossOriginPost("/api/addons", """{"urls":["https://example.com"]}""")))
        assertEquals(false, proposed)
    }

    @Test
    fun `repository server rejects a cross-origin post`() {
        var proposed = false
        val server = RepositoryConfigServer(
            context = context,
            currentRepositoriesProvider = { emptyList() },
            onChangeProposed = { proposed = true },
            port = 0
        )

        assertRejected(server.serve(crossOriginPost("/api/repositories", """{"urls":["https://example.com"]}""")))
        assertEquals(false, proposed)
    }

    @Test
    fun `debrid formatter server rejects a cross-origin post`() {
        var saved: DebridFormatterSettings? = null
        val server = DebridFormatterConfigServer(
            currentSettingsProvider = { DebridFormatterSettings(nameTemplate = "", descriptionTemplate = "") },
            onSettingsChanged = { saved = it },
            port = 0
        )

        assertRejected(
            server.serve(
                crossOriginPost(
                    "/api/settings",
                    """{"nameTemplate":"{stream.resolution}","descriptionTemplate":"x"}"""
                )
            )
        )
        assertEquals(null, saved)
    }

    @Test
    fun `badge server rejects a cross-origin post`() {
        var saved: StreamBadgeSettings? = null
        val server = StreamBadgeConfigServer(
            currentSettingsProvider = { StreamBadgeSettings() },
            onSettingsChanged = { saved = it },
            port = 0
        )

        assertRejected(server.serve(crossOriginPost("/api/settings", """{"showFileSizeBadges":false}""")))
        assertEquals(null, saved)
    }

    @Test
    fun `custom poster server rejects a cross-origin post`() {
        var saved: String? = null
        val server = CustomPosterConfigServer(
            currentPatternProvider = { "" },
            onPatternChanged = { saved = it },
            port = 0
        )

        assertRejected(server.serve(crossOriginPost("/api/pattern", """{"pattern":"https://example.com/{id}"}""")))
        assertEquals(null, saved)
    }

    private fun assertRejected(response: NanoHTTPD.Response) {
        assertEquals(NanoHTTPD.Response.Status.FORBIDDEN, response.status)
    }

    private fun crossOriginPost(uri: String, body: String): NanoHTTPD.IHTTPSession {
        val bytes = body.toByteArray(Charsets.UTF_8)
        return object : NanoHTTPD.IHTTPSession {
            override fun execute() = Unit
            override fun getCookies(): NanoHTTPD.CookieHandler? = null
            override fun getHeaders(): Map<String, String> = mapOf(
                "host" to "192.168.1.10:8090",
                "origin" to "https://evil.example",
                "content-length" to bytes.size.toString()
            )
            override fun getInputStream() = ByteArrayInputStream(bytes)
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
    }
}
