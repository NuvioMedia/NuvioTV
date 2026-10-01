package com.nuvio.tv.data.floppy

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyAuthStoreTest {
    @Test
    fun `addresses are normalised to a plain origin`() {
        assertEquals("https://floppy.example.com", normalizeFloppyBaseUrl(" floppy.example.com "))
        assertEquals("https://floppy.example.com", normalizeFloppyBaseUrl("https://floppy.example.com/"))
        assertEquals("http://192.168.1.20:8000", normalizeFloppyBaseUrl("http://192.168.1.20:8000/"))
        assertEquals("http://nas.local:8000", normalizeFloppyBaseUrl("http://nas.local:8000/api/v1/"))
        assertEquals("https://example.com/floppy", normalizeFloppyBaseUrl("https://example.com/floppy/api"))
        assertEquals("http://[::1]:8000", normalizeFloppyBaseUrl("http://[::1]:8000"))
    }

    @Test
    fun `addresses that are not a plain server are rejected`() {
        for (raw in listOf("", "   ", "ftp://example.com", "https://user:pass@example.com", "https://example.com?x=1", "https://example.com/#top", "not a url")) {
            assertNull(raw, normalizeFloppyBaseUrl(raw))
        }
    }

    @Test
    fun `credentials survive a restart`() {
        val persistence = MemoryPersistence()
        FloppyAuthStore(persistence).save(credentials())

        val reloaded = FloppyAuthStore(persistence)

        assertTrue(reloaded.state.value.isConnected)
        assertEquals("https://floppy.example.com", reloaded.state.value.baseUrl)
        assertEquals("flp_secret", reloaded.credentials()?.token)
    }

    @Test
    fun `profiles keep separate connections`() {
        val store = FloppyAuthStore(MemoryPersistence())
        store.save(credentials())

        store.selectProfile(2)
        assertFalse(store.state.value.isConnected)
        store.save(credentials("https://other.example.com", "flp_other"))
        store.selectProfile(1)

        assertEquals("flp_secret", store.credentials()?.token)
        store.selectProfile(2)
        assertEquals("flp_other", store.credentials()?.token)
    }

    @Test
    fun `a failed write never reports a connection`() {
        val persistence = MemoryPersistence().apply { failWrites = true }
        val store = FloppyAuthStore(persistence)

        assertThrows(IOException::class.java) { store.save(credentials()) }

        assertFalse(store.state.value.isConnected)
        assertNull(store.credentials())
    }

    @Test
    fun `disconnect and profile removal clear only what they name`() {
        val store = FloppyAuthStore(MemoryPersistence())
        store.save(credentials())
        store.selectProfile(2)
        store.save(credentials(token = "flp_two"))

        store.removeProfile(1)
        assertTrue(store.state.value.isConnected)
        store.selectProfile(1)
        assertFalse(store.state.value.isConnected)

        store.selectProfile(2)
        store.disconnect()
        assertFalse(store.state.value.isConnected)
        assertNull(store.credentials())
    }

    @Test
    fun `clearing all profiles disconnects everyone`() {
        val persistence = MemoryPersistence()
        val store = FloppyAuthStore(persistence)
        store.save(credentials())

        store.clearAllProfiles()

        assertFalse(store.state.value.isConnected)
        assertNull(FloppyAuthStore(persistence).credentials())
    }

    @Test
    fun `damaged stored credentials load as disconnected`() {
        val persistence = MemoryPersistence().apply { write(1, "{not json") }

        assertFalse(FloppyAuthStore(persistence).state.value.isConnected)
    }

    @Test
    fun `the token never appears in text output`() {
        assertFalse(credentials().toString().contains("flp_secret"))
    }

    private fun credentials(baseUrl: String = "https://floppy.example.com", token: String = "flp_secret") =
        FloppyCredentials(baseUrl, token)
}

internal class MemoryPersistence : FloppyAuthPersistence {
    private val values = mutableMapOf<Int, String>()
    var failWrites = false

    override fun read(profileId: Int): String? = values[profileId]

    override fun write(profileId: Int, value: String?) {
        if (failWrites) throw IOException("disk full")
        if (value == null) values.remove(profileId) else values[profileId] = value
    }

    override fun clear() {
        values.clear()
    }
}
