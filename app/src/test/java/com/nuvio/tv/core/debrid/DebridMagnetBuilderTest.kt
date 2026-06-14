package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DebridMagnetBuilderTest {
    @Test
    fun `fromStream builds magnet link using infoHash when present`() {
        val stream = stream(infoHash = "1234567890123456789012345678901234567890")
        val magnet = DebridMagnetBuilder.fromStream(stream)
        assertEquals("magnet:?xt=urn:btih:1234567890123456789012345678901234567890&dn=Torrent.mkv", magnet)
    }

    @Test
    fun `fromStream extracts infoHash from torrent url when infoHash is null`() {
        val stream = stream(url = "torrent://1234567890123456789012345678901234567890/0", infoHash = null)
        val magnet = DebridMagnetBuilder.fromStream(stream)
        assertEquals("magnet:?xt=urn:btih:1234567890123456789012345678901234567890&dn=Torrent.mkv", magnet)
    }

    @Test
    fun `fromStream extracts infoHash from magnet url when infoHash is null`() {
        val stream = stream(url = "magnet:?xt=urn:btih:1234567890123456789012345678901234567890&dn=Test", infoHash = null)
        val magnet = DebridMagnetBuilder.fromStream(stream)
        assertEquals("magnet:?xt=urn:btih:1234567890123456789012345678901234567890&dn=Test", magnet)
    }

    @Test
    fun `fromStream returns null for non-torrent and non-magnet stream`() {
        val stream = stream(url = "https://example.com/video.mkv", infoHash = null)
        val magnet = DebridMagnetBuilder.fromStream(stream)
        assertNull(magnet)
    }

    private fun stream(
        name: String = "Torrent",
        url: String? = null,
        infoHash: String? = null
    ): Stream =
        Stream(
            name = name,
            title = name,
            description = null,
            url = url,
            ytId = null,
            infoHash = infoHash,
            fileIdx = null,
            externalUrl = null,
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                filename = "$name.mkv"
            ),
            addonName = "Addon",
            addonLogo = null
        )
}
