package com.nuvio.tv.core.torrent

import android.content.Context
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrServerBinaryTest {

    @Test
    fun `default base url is the local binary`() = runTest {
        val harness = harness()

        assertEquals("http://127.0.0.1:8091", harness.binary.baseUrl)
        assertFalse(harness.binary.isUsingCustomEndpoint)
    }

    @Test
    fun `custom endpoint overrides base url`() = runTest {
        val harness = harness()

        harness.settings.setCustomTorrServerUrl("http://10.0.0.5:8090")

        assertTrue(harness.binary.isUsingCustomEndpoint)
        assertEquals("http://10.0.0.5:8090", harness.binary.baseUrl)
    }

    @Test
    fun `start is a no-op when a custom endpoint is configured`() = runTest {
        val harness = harness()
        harness.settings.setCustomTorrServerUrl("https://ts.example.com")

        // Must not attempt to launch the bundled binary or hit the network.
        harness.binary.start()

        assertTrue(harness.binary.isUsingCustomEndpoint)
        assertEquals("https://ts.example.com", harness.binary.baseUrl)
    }

    @Test
    fun `stop is a no-op when a custom endpoint is configured`() = runTest {
        val harness = harness()
        harness.settings.setCustomTorrServerUrl("http://10.0.0.5:8090")

        harness.binary.stop()

        assertTrue(harness.binary.isUsingCustomEndpoint)
        assertEquals("http://10.0.0.5:8090", harness.binary.baseUrl)
    }

    @Test
    fun `clearing the custom endpoint falls back to the local binary`() = runTest {
        val harness = harness()
        harness.settings.setCustomTorrServerUrl("http://10.0.0.5:8090")
        assertTrue(harness.binary.isUsingCustomEndpoint)

        harness.settings.setCustomTorrServerUrl("")

        assertFalse(harness.binary.isUsingCustomEndpoint)
        assertEquals("http://127.0.0.1:8091", harness.binary.baseUrl)
    }

    private fun harness(): Harness {
        val settings = TorrentSettings(InMemoryPreferencesDataStore())
        return Harness(
            settings = settings,
            binary = TorrServerBinary(mockk<Context>(relaxed = true), settings)
        )
    }

    private data class Harness(
        val settings: TorrentSettings,
        val binary: TorrServerBinary
    )
}