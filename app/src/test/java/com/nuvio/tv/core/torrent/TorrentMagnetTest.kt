package com.nuvio.tv.core.torrent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TorrentMagnetTest {
    @Test
    fun `v1 hash and trackers are canonical encoded and deduplicated`() {
        val hash = "ABCDEF0123456789ABCDEF0123456789ABCDEF01"
        val tracker = "udp://tracker.example:80/announce?key=hello world"
        val magnet = buildMagnetUri(hash, listOf("", tracker, " ", tracker))

        assertTrue(magnet.startsWith("magnet:?xt=urn:btih:${hash.lowercase()}"))
        assertEquals(1, magnet.windowed("&tr=".length).count { it == "&tr=" })
        assertTrue(magnet.endsWith("udp%3A%2F%2Ftracker.example%3A80%2Fannounce%3Fkey%3Dhello%20world"))
    }

    @Test
    fun `v2 hash uses multihash topic`() {
        val hash = "a".repeat(64)
        assertTrue(buildMagnetUri(hash, emptyList()).contains("xt=urn:btmh:1220$hash"))
    }

    @Test
    fun `invalid hash is rejected before native work`() {
        assertThrows(IllegalArgumentException::class.java) {
            buildMagnetUri("not-a-hash", emptyList())
        }
    }
}
