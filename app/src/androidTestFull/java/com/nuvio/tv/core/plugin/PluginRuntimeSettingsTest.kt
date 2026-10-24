package com.nuvio.tv.core.plugin

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PluginRuntimeSettingsTest {

    @Test
    fun onSettingsAndSavedSettingsAreAvailableToScraper() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().context
        val code = context.assets.open("plugin-settings-test.js")
            .bufferedReader()
            .use { it.readText() }
        val runtime = PluginRuntime()

        val layoutJson = runtime.getSettingsLayout("test-repository:settings-test", code)
        assertNotNull("onSettings() should return a layout", layoutJson)

        val layout = JSONArray(layoutJson!!)
        assertEquals(7, layout.length())
        assertEquals("toggle", layout.getJSONObject(0).getString("type"))
        assertEquals("header", layout.getJSONObject(1).getString("type"))
        assertEquals("info", layout.getJSONObject(2).getString("type"))
        assertEquals("text", layout.getJSONObject(3).getString("type"))
        assertTrue(layout.getJSONObject(4).getBoolean("isPassword"))
        assertEquals("select", layout.getJSONObject(5).getString("type"))
        assertEquals(3, layout.getJSONObject(5).getJSONArray("options").length())

        val localStorage = mutableMapOf("previousScrape" to "stored-before-run")
        val results = runtime.executePlugin(
            code = code,
            tmdbId = "12345",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "test-repository:settings-test",
            scraperSettings = mapOf(
                "update-last-scrape" to false,
                "test-text" to "saved text",
                "test-select" to "option-2",
                "test-toggle" to false
            ),
            localStorage = localStorage
        )

        assertEquals(1, results.size)
        val observed = JSONObject(results.single().title)
        assertFalse(observed.getBoolean("updateLastScrape"))
        assertEquals("saved text", observed.getString("text"))
        assertEquals("option-2", observed.getString("select"))
        assertFalse(observed.getBoolean("toggle"))
        assertEquals("stored-before-run", observed.getString("previousScrape"))
        assertTrue(observed.getBoolean("hasSettingsGlobal"))
        assertEquals("stored-before-run", localStorage["previousScrape"])

        val updateResults = runtime.executePlugin(
            code = code,
            tmdbId = "12345",
            mediaType = "movie",
            season = null,
            episode = null,
            scraperId = "test-repository:settings-test",
            scraperSettings = mapOf("update-last-scrape" to true),
            localStorage = localStorage
        )
        assertEquals(1, updateResults.size)
        val defaultsObserved = JSONObject(updateResults.single().title)
        assertTrue(defaultsObserved.getBoolean("updateLastScrape"))
        assertEquals("default-text", defaultsObserved.getString("text"))
        assertEquals("default-option", defaultsObserved.getString("select"))
        assertTrue(defaultsObserved.getBoolean("toggle"))
        assertEquals("stored-before-run", defaultsObserved.getString("previousScrape"))
        assertEquals("updated-by-scrape", localStorage["previousScrape"])
    }
}
