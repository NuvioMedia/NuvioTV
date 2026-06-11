package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.domain.model.extractInfoHashFromTorrentLink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamMapperTorrentTest {

    private val hex40 = "0123456789abcdef0123456789abcdef01234567"

    @Test
    fun `torrent scheme url resolves info hash and is treated as torrent`() {
        val stream = StreamDto(url = "torrent://$hex40", infoHash = null)
            .toDomain(addonName = "Test", addonLogo = null)

        assertEquals(hex40, stream.infoHash)
        // A torrent:// URL must never be surfaced as a playable HTTP stream.
        assertNull(stream.getStreamUrl())
        assertTrue(stream.isTorrent())
    }

    @Test
    fun `magnet url without infoHash field resolves info hash`() {
        val stream = StreamDto(
            url = "magnet:?xt=urn:btih:$hex40&dn=Example",
            infoHash = null
        ).toDomain(addonName = "Test", addonLogo = null)

        assertEquals(hex40, stream.infoHash)
        assertNull(stream.getStreamUrl())
        assertTrue(stream.isTorrent())
    }

    @Test
    fun `dedicated infoHash field is preferred over url extraction`() {
        val stream = StreamDto(url = "torrent://$hex40", infoHash = "DEDICATED")
            .toDomain(addonName = "Test", addonLogo = null)

        assertEquals("DEDICATED", stream.infoHash)
    }

    @Test
    fun `plain http url is unaffected and not a torrent`() {
        val httpUrl = "https://cdn.example.com/video.mkv"
        val stream = StreamDto(url = httpUrl, infoHash = null)
            .toDomain(addonName = "Test", addonLogo = null)

        assertNull(stream.infoHash)
        assertEquals(httpUrl, stream.getStreamUrl())
        assertFalse(stream.isTorrent())
    }

    @Test
    fun `extractInfoHashFromTorrentLink handles hex base32 and rejects non-torrent`() {
        val base32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" // 32 base32 chars
        assertEquals(hex40, extractInfoHashFromTorrentLink("torrent://$hex40"))
        assertEquals(hex40, extractInfoHashFromTorrentLink("magnet:?xt=urn:btih:$hex40"))
        assertEquals(base32, extractInfoHashFromTorrentLink("magnet:?xt=urn:btih:$base32"))
        assertNull(extractInfoHashFromTorrentLink("https://example.com/x.mkv"))
        assertNull(extractInfoHashFromTorrentLink(null))
        assertNull(extractInfoHashFromTorrentLink("torrent://null"))
    }
}
