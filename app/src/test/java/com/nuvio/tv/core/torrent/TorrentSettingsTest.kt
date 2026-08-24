package com.nuvio.tv.core.torrent

import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class TorrentSettingsTest {

    private val customKey = stringPreferencesKey("custom_torr_server_url")

    @Test
    fun `defaults are used when nothing is stored`() = runBlocking {
        val settings = TorrentSettings(InMemoryPreferencesDataStore())

        val value = settings.settings.first()

        assertEquals(TorrentSettingsData(), value)
        assertEquals("", value.customTorrServerUrl)
        assertEquals("", settings.currentCustomTorrServerUrl)
    }

    @Test
    fun `setCustomTorrServerUrl persists a trimmed value`() = runBlocking {
        val settings = TorrentSettings(InMemoryPreferencesDataStore())
        settings.setCustomTorrServerUrl("  http://10.0.0.5:8090/  ")

        val value = withTimeout(5_000) {
            settings.settings.map { it.customTorrServerUrl }.first { it == "http://10.0.0.5:8090/" }
        }

        assertEquals("http://10.0.0.5:8090/", value)
        assertEquals("http://10.0.0.5:8090/", settings.currentCustomTorrServerUrl)
    }

    @Test
    fun `clearing the custom endpoint restores the built-in default`() = runBlocking {
        val settings = TorrentSettings(InMemoryPreferencesDataStore())
        settings.setCustomTorrServerUrl("http://10.0.0.5:8090")

        settings.setCustomTorrServerUrl("")

        val value = withTimeout(5_000) {
            settings.settings.map { it.customTorrServerUrl }.first { it.isEmpty() }
        }
        assertEquals("", value)
        assertEquals("", settings.currentCustomTorrServerUrl)
    }

    @Test
    fun `stored endpoint is exposed through the settings flow`() = runBlocking {
        val store = InMemoryPreferencesDataStore()
        val settings = TorrentSettings(store)
        store.set(customKey, "https://ts.example.com")

        val value = withTimeout(5_000) {
            settings.settings.map { it.customTorrServerUrl }.first { it == "https://ts.example.com" }
        }

        assertEquals("https://ts.example.com", value)
        assertEquals("https://ts.example.com", settings.currentCustomTorrServerUrl)
        assertFalse(settings.settings.first().p2pEnabled)
    }
}