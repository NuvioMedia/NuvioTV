package com.omnio.tv.data.remote.dto.aiometadata

import com.omnio.tv.domain.model.AioConfigInnerDto
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AioConfigInnerDtoJsonAdapterTest {

    private val moshi: Moshi = Moshi.Builder()
        .add(AioConfigInnerDtoJsonAdapter.Factory)
        .add(KotlinJsonAdapterFactory())
        .build()

    private val adapter = moshi.adapter(AioConfigInnerDto::class.java)

    @Test
    fun `toJson flattens settings catch-all to root`() {
        val dto = AioConfigInnerDto(
            providers = mapOf("movie" to "tmdb", "series" to "tvdb"),
            apiKeys = mapOf("tmdb" to "key-tmdb", "tvdb" to "key-tvdb"),
            catalogs = listOf(mapOf("id" to "tmdb.trending", "enabled" to true)),
            settings = mapOf(
                "language" to "en-US",
                "tvdbSeasonType" to "default",
                "mal" to mapOf("useImdbIdForCatalogAndSearch" to true),
                "tmdb" to mapOf("scrapeImdb" to true),
                "nuvio_provider_tmdb" to true,
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = moshi.adapter(Map::class.java).fromJson(adapter.toJson(dto)) as Map<String, Any?>

        // Reserved slots stay at their fixed names.
        assertEquals(mapOf("movie" to "tmdb", "series" to "tvdb"), decoded["providers"])
        assertEquals(mapOf("tmdb" to "key-tmdb", "tvdb" to "key-tvdb"), decoded["apiKeys"])
        assertTrue(decoded["catalogs"] is List<*>)

        // Catch-all fields live at the root (not under a "settings" envelope).
        assertEquals("en-US", decoded["language"])
        assertEquals("default", decoded["tvdbSeasonType"])
        assertEquals(true, decoded["nuvio_provider_tmdb"])
        assertEquals(
            mapOf("useImdbIdForCatalogAndSearch" to true),
            decoded["mal"],
        )
        assertEquals(mapOf("scrapeImdb" to true), decoded["tmdb"])

        // There must be no "settings" wrapper on the wire.
        assertNull("settings wrapper must not be written", decoded["settings"])
    }

    @Test
    fun `toJson drops reserved keys if they leak into the settings map`() {
        val dto = AioConfigInnerDto(
            providers = mapOf("movie" to "tmdb"),
            apiKeys = mapOf("tmdb" to "key"),
            catalogs = emptyList(),
            settings = mapOf(
                // These should never be allowed to shadow the dedicated slots.
                "providers" to mapOf("bogus" to true),
                "apiKeys" to mapOf("bogus" to "x"),
                "catalogs" to emptyList<Any?>(),
                "settings" to mapOf("bogus" to true),
                "language" to "en-US",
            ),
        )

        @Suppress("UNCHECKED_CAST")
        val decoded = moshi.adapter(Map::class.java).fromJson(adapter.toJson(dto)) as Map<String, Any?>

        assertEquals(mapOf("movie" to "tmdb"), decoded["providers"])
        assertEquals(mapOf("tmdb" to "key"), decoded["apiKeys"])
        assertNull(decoded["settings"])
        assertEquals("en-US", decoded["language"])
    }

    @Test
    fun `fromJson collects root-level catch-all into settings`() {
        val json = """
            {
              "providers": { "movie": "tmdb" },
              "apiKeys": { "tmdb": "key" },
              "catalogs": [ { "id": "tmdb.trending" } ],
              "language": "en-US",
              "tvdbSeasonType": "default",
              "mal": { "useImdbIdForCatalogAndSearch": true }
            }
        """.trimIndent()

        val decoded = adapter.fromJson(json)!!

        assertEquals("tmdb", decoded.providers["movie"])
        assertEquals("key", decoded.apiKeys["tmdb"])
        assertEquals("en-US", decoded.settings["language"])
        assertEquals("default", decoded.settings["tvdbSeasonType"])
        assertEquals(
            mapOf("useImdbIdForCatalogAndSearch" to true),
            decoded.settings["mal"],
        )
    }

    @Test
    fun `fromJson unwraps legacy nested settings wrapper`() {
        val json = """
            {
              "providers": {},
              "apiKeys": {},
              "catalogs": [],
              "settings": {
                "language": "en-US",
                "mal": { "useImdbIdForCatalogAndSearch": true },
                "showDisabledCatalogs": false
              },
              "showDisabledCatalogs": true
            }
        """.trimIndent()

        val decoded = adapter.fromJson(json)!!

        // Root-level entries must win on conflict.
        assertEquals(
            "root wins over legacy nested settings",
            true,
            decoded.settings["showDisabledCatalogs"],
        )
        // Entries only present under the nested wrapper are still rescued.
        assertEquals("en-US", decoded.settings["language"])
        assertEquals(
            mapOf("useImdbIdForCatalogAndSearch" to true),
            decoded.settings["mal"],
        )
    }

    @Test
    fun `round trip through adapter preserves data without reintroducing settings wrapper`() {
        val original = AioConfigInnerDto(
            providers = mapOf("movie" to "tmdb"),
            apiKeys = mapOf("tmdb" to "key"),
            catalogs = listOf(mapOf("id" to "tmdb.trending", "enabled" to true)),
            settings = mapOf(
                "language" to "en-US",
                "mal" to mapOf("useImdbIdForCatalogAndSearch" to true),
            ),
        )

        val json = adapter.toJson(original)
        val restored = adapter.fromJson(json)!!

        assertEquals(original.providers, restored.providers)
        assertEquals(original.apiKeys, restored.apiKeys)
        assertEquals(original.catalogs, restored.catalogs)
        assertEquals(original.settings, restored.settings)

        // Defensive: verify the wire really was flat.
        @Suppress("UNCHECKED_CAST")
        val wire = moshi.adapter(Map::class.java).fromJson(json) as Map<String, Any?>
        assertNull(wire["settings"])
        assertEquals("en-US", wire["language"])
    }
}
