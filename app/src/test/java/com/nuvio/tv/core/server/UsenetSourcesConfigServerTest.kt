package com.nuvio.tv.core.server

import com.nuvio.tv.core.usenet.ProviderTestResult
import com.nuvio.tv.core.usenet.UsenetIndexer
import com.nuvio.tv.core.usenet.UsenetProvider
import com.nuvio.tv.core.usenet.UsenetSourceConfiguration
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class UsenetSourcesConfigServerTest {
    private val provider = UsenetProvider(id = "p1", name = "Primary", host = "news.example.com",
        username = "user", password = "provider-secret", enabled = false)
    private val indexer = UsenetIndexer(id = "i1", name = "Indexer", apiUrl = "https://indexer.example/api", apiKey = "api-secret")

    private inner class FakeSetup : UsenetPhoneSetup {
        var configuration = UsenetSourceConfiguration(providers = listOf(provider), indexers = listOf(indexer))
        var saved = mutableListOf<String>()
        var tested: UsenetProvider? = null
        override fun configuration() = configuration
        override fun save(name: String, change: (UsenetSourceConfiguration) -> UsenetSourceConfiguration): Boolean {
            configuration = change(configuration).also { it.validate() }
            saved += name
            return true
        }
        override fun testProvider(provider: UsenetProvider): ProviderTestResult {
            tested = provider
            return ProviderTestResult.AUTH
        }
        override fun testIndexer(indexer: UsenetIndexer) = true
    }

    private val setup = FakeSetup()
    private lateinit var server: UsenetSourcesConfigServer
    private val http = OkHttpClient()

    @Before fun start() {
        server = UsenetSourcesConfigServer.startOnAvailablePort(setup, page = { "<html>page</html>" }, text = { "text-$it" }, startPort = 18100)!!
    }

    @After fun stop() = server.stop()

    private fun call(path: String, body: String? = null, token: String? = server.token): Pair<Int, String> {
        val request = Request.Builder().url("http://127.0.0.1:${server.listeningPort}$path").apply {
            if (token != null) header(UsenetSourcesConfigServer.TOKEN_HEADER, token)
            if (body != null) post(body.toRequestBody("application/json".toMediaType()))
        }.build()
        return http.newCall(request).execute().use { it.code to it.body!!.string() }
    }

    @Test fun `api needs the token from the QR code and never returns saved secrets`() {
        assertEquals(200, call("/", token = null).first)
        assertEquals(403, call("/api/sources", token = null).first)
        assertEquals(403, call("/api/sources", token = "0".repeat(32)).first)
        assertEquals(403, call("/api/providers", """{"name":"X","host":"x.example"}""", token = null).first)
        assertEquals(listOf(provider), setup.configuration.providers)

        val (code, body) = call("/api/sources")
        assertEquals(200, code)
        assertFalse(body.contains("provider-secret"))
        assertFalse(body.contains("api-secret"))
        assertTrue(body.contains("\"hasPassword\":true"))
        assertTrue(body.contains("\"hasKey\":true"))
        assertTrue(body.contains("news.example.com"))
    }

    @Test fun `a blank password or key keeps the saved one, and an edit keeps the id and enabled state`() {
        assertEquals(200, call("/api/providers",
            """{"id":"p1","name":"Renamed","host":"eu.example.com","port":443,"tls":true,"username":"user","password":"","connections":30,"priority":2}""").first)
        val edited = setup.configuration.providers.single()
        assertEquals(provider.copy(name = "Renamed", host = "eu.example.com", port = 443, connections = 30, priority = 2), edited)

        assertEquals(200, call("/api/indexers", """{"id":"i1","name":"Indexer","apiUrl":"https://indexer.example/api","apiKey":" ","priority":3}""").first)
        assertEquals(indexer.copy(priority = 3), setup.configuration.indexers.single())

        call("/api/indexers", """{"id":"i1","name":"Indexer","apiUrl":"https://indexer.example/api","apiKey":"new-key"}""")
        assertEquals("new-key", setup.configuration.indexers.single().apiKey)
        assertEquals(listOf("Renamed", "Indexer", "Indexer"), setup.saved)
    }

    @Test fun `new sources are added and invalid ones are rejected without saving`() {
        val (code, body) = call("/api/providers", """{"name":"Backup","host":"backup.example","password":"pw","priority":2}""")
        assertEquals(200, code)
        val added = setup.configuration.providers.last()
        assertTrue(body.contains(added.id))
        assertEquals("pw", added.password)
        assertNotEquals("p1", added.id)

        assertEquals(400, call("/api/providers", """{"name":"Bad","host":"http://bad.example/path"}""").first)
        assertEquals(400, call("/api/indexers", """{"name":"Bad","apiUrl":"not a url"}""").first)
        assertEquals(400, call("/api/indexers", "not json").first)
        assertEquals(400, call("/api/providers", "{" + " ".repeat(20_000) + "}").first)
        assertEquals(2, setup.configuration.providers.size)
        assertEquals(1, setup.configuration.indexers.size)
    }

    @Test fun `testing an edited provider uses its saved password`() {
        val (code, body) = call("/api/providers/test", """{"id":"p1","name":"Primary","host":"news.example.com","username":"user"}""")
        assertEquals(200, code)
        assertEquals("provider-secret", setup.tested!!.password)
        assertTrue(body.contains("\"ok\":false"))
        assertTrue(setup.saved.isEmpty())
    }
}
