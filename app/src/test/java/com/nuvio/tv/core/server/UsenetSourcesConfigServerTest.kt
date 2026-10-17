package com.nuvio.tv.core.server

import com.nuvio.tv.core.streams.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
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
        var available = true
        var saved = mutableListOf<String>()
        var tested: UsenetProvider? = null
        override fun configuration() = configuration.takeIf { available }
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
            """{"id":"p1","name":"Renamed","host":"news.example.com","port":563,"tls":true,"username":"user","password":"","connections":30,"priority":2}""").first)
        val edited = setup.configuration.providers.single()
        assertEquals(provider.copy(name = "Renamed", connections = 30, priority = 2), edited)

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

    @Test fun `a saved password or key is never sent to a changed server`() {
        // Another host, port, TLS setting or username: the blank password must not reuse the saved one.
        val saved = mapOf("host" to "\"news.example.com\"", "port" to "563", "tls" to "true", "username" to "\"user\"")
        for (change in listOf("host" to "\"attacker.example\"", "port" to "119", "tls" to "false", "username" to "\"other\"")) {
            val fields = (saved + change).entries.joinToString(",") { "\"${it.key}\":${it.value}" }
            val body = """{"id":"p1","name":"Primary",$fields}"""
            assertEquals(change.first, 400, call("/api/providers/test", body).first)
            assertEquals(change.first, 400, call("/api/providers", body).first)
        }
        assertNull(setup.tested)
        assertEquals(listOf(provider), setup.configuration.providers)
        // With the password entered again it is used, and saved.
        assertEquals(200, call("/api/providers/test", """{"id":"p1","name":"Primary","host":"other.example","username":"user","password":"new"}""").first)
        assertEquals("new", setup.tested!!.password)

        assertEquals(400, call("/api/indexers/test", """{"id":"i1","name":"Indexer","apiUrl":"https://attacker.example/api"}""").first)
        assertEquals(400, call("/api/indexers", """{"id":"i1","name":"Indexer","apiUrl":"https://indexer.example","apiPath":"/v2/api"}""").first)
        assertEquals(listOf(indexer), setup.configuration.indexers)
    }

    @Test fun `keys in an indexer url query never reach the phone and survive an edit`() {
        val keyed = indexer.copy(apiUrl = "https://indexer.example/api?apikey=url-secret&cat=2000")
        setup.configuration = setup.configuration.copy(indexers = listOf(keyed))
        val (_, sources) = call("/api/sources")
        assertFalse(sources.contains("url-secret"))
        assertTrue(sources.contains("\"apiUrl\":\"https://indexer.example/api\""))
        // The phone sends back the URL it was given: the saved one, query included, is kept.
        assertEquals(200, call("/api/indexers", """{"id":"i1","name":"Renamed","apiUrl":"https://indexer.example/api"}""").first)
        assertEquals(keyed.copy(name = "Renamed"), setup.configuration.indexers.single())
    }
    @Test fun `ranking endpoints require the token and return no provider credentials`() {
        assertEquals(403, call("/api/ranking", token = null).first)
        assertEquals(403, call("/api/ranking/preview", "{}", token = null).first)
        val (code, body) = call("/api/ranking")
        assertEquals(200, code)
        assertFalse(body.contains("provider-secret"))
        assertFalse(body.contains("api-secret"))
        assertFalse(body.contains("news.example.com"))
        val ranking = Json.decodeFromString<PhoneRanking>(body)
        assertEquals(StreamRankingPreset.ORIGINAL, ranking.settings.preset)
        assertEquals(4, ranking.presets.size)
        assertTrue(ranking.presets.any { it.id == StreamRankingPreset.BALANCED })
    }

    @Test fun `saving and previewing a profile preserve all sources and preview does not save`() {
        val original = setup.configuration
        val profile = StreamRankingPresets.profile(StreamRankingPreset.BEST_QUALITY)
        val settings = StreamRankingSettings(StreamRankingPreset.CUSTOM, profile.copy(name = "Living room"))
        val preview = RankingPreviewRequest(settings, listOf(
            RankingPreviewSample("Film.2160p.WEB-DL-FLUX", 10.0),
            RankingPreviewSample("Film.1080p.BluRay.REMUX-CiNEPHiLES", 20.0)))
        val (code, body) = call("/api/ranking/preview", Json.encodeToString(preview))
        assertEquals(200, code)
        assertTrue(setup.saved.isEmpty())
        assertEquals(original, setup.configuration)
        val result = Json.decodeFromString<RankingPreview>(body)
        assertTrue(result.results.first().title.contains("1080p"))
        assertEquals(300, result.results.first().score)
        assertEquals(profile.copy(name = "Living room"), result.profile)

        assertEquals(200, call("/api/ranking", Json.encodeToString(settings)).first)
        assertEquals(settings, setup.configuration.ranking)
        assertEquals(original.providers, setup.configuration.providers)
        assertEquals(original.indexers, setup.configuration.indexers)
    }

    @Test fun `bad profile regex version and oversized preview are rejected without saving`() {
        val bad = StreamRankingSettings(StreamRankingPreset.CUSTOM, StreamRankingProfile(rules = listOf(
            StreamRankingRule(conditions = listOf(StreamRankingCondition(StreamRankingField.TITLE, StreamRankingOperator.MATCHES, "(?<=x)y"))))))
        assertEquals(400, call("/api/ranking", Json.encodeToString(bad)).first)
        assertEquals(400, call("/api/ranking", "not json").first)
        assertEquals(400, call("/api/ranking", Json.encodeToString(StreamRankingSettings(StreamRankingPreset.CUSTOM,
            StreamRankingProfile(version = 2)))).first)
        assertEquals(400, call("/api/ranking/preview", Json.encodeToString(RankingPreviewRequest(StreamRankingSettings(), List(51) { RankingPreviewSample("Film") }))).first)
        assertTrue(setup.saved.isEmpty())
    }

    @Test fun `phone sorting cannot read preview or save once its TV profile is unavailable`() {
        setup.available = false
        assertEquals(409, call("/api/ranking").first)
        assertEquals(409, call("/api/ranking", Json.encodeToString(StreamRankingSettings(StreamRankingPreset.BALANCED))).first)
        assertEquals(409, call("/api/ranking/preview", Json.encodeToString(RankingPreviewRequest(StreamRankingSettings(), listOf(RankingPreviewSample("Film"))))).first)
        assertTrue(setup.saved.isEmpty())
    }

    @Test fun `preview uses the configured indexer hierarchy without exposing sources`() {
        val profile = StreamRankingProfile(sort = listOf(StreamRankingSort(StreamRankingKey.INDEXER)), topPerBucket = 0)
        val settings = StreamRankingSettings(StreamRankingPreset.CUSTOM, profile)
        val response = call("/api/ranking/preview", Json.encodeToString(RankingPreviewRequest(settings,
            listOf(RankingPreviewSample("First alphabetically", indexer = "Unknown"), RankingPreviewSample("Last alphabetically", indexer = "Indexer")))))
        assertEquals(200, response.first)
        val preview = Json.decodeFromString<RankingPreview>(response.second)
        assertEquals("Last alphabetically", preview.results.first().title)
        assertFalse(response.second.contains("api-secret"))
    }

}
