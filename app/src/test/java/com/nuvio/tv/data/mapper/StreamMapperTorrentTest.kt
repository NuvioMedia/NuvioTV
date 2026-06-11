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
    private val base32 = "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"

    @Test
    fun `torrent scheme url resolves info hash and is treated as torrent`() {
        val stream = StreamDto(url = "torrent://$hex40", infoHash = null)
            .toDomain(addonName = "Test", addonLogo = null)

        assertEquals(hex40, stream.infoHash)
        // A torrent:// URL must never be surfaced as a playable HTTP stream.
        assertNull(stream.getStreamUrl())
        assertTrue(stream.isTorrent())
        // And it is not a magnet, so debrid magnet consumers must not receive it.
        assertNull(stream.torrentMagnetUri())
    }

    @Test
    fun `magnet url without infoHash field resolves info hash`() {
        val magnet = "magnet:?xt=urn:btih:$hex40&dn=Example"
        val stream = StreamDto(url = magnet, infoHash = null)
            .toDomain(addonName = "Test", addonLogo = null)

        assertEquals(hex40, stream.infoHash)
        assertNull(stream.getStreamUrl())
        assertTrue(stream.isTorrent())
        assertEquals(magnet, stream.torrentMagnetUri())
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
    fun `torrent url without extractable hash is unplayable rather than a torrent sentinel`() {
        val stream = StreamDto(url = "torrent://null", infoHash = null)
            .toDomain(addonName = "Test", addonLogo = null)

        assertNull(stream.infoHash)
        assertNull(stream.getStreamUrl())
        // Not classified as a torrent: there is no hash to hand to TorrServer,
        // so the player must not be launched with a torrent://null URL.
        assertFalse(stream.isTorrent())
    }

    @Test
    fun `extraction parses hash from expected position only`() {
        assertEquals(hex40, extractInfoHashFromTorrentLink("torrent://$hex40"))
        assertEquals(hex40, extractInfoHashFromTorrentLink("torrent://$hex40/0?probe=1"))
        assertEquals(hex40, extractInfoHashFromTorrentLink("magnet:?xt=urn:btih:$hex40"))
        assertEquals(hex40, extractInfoHashFromTorrentLink("magnet:?xt=URN:BTIH:$hex40"))
        assertEquals(base32, extractInfoHashFromTorrentLink("magnet:?xt=urn:btih:$base32&dn=x"))
        // dn parameter before xt must not be mistaken for the hash.
        assertEquals(
            hex40,
            extractInfoHashFromTorrentLink(
                "magnet:?dn=SomeStraightAlphaNumericRunName32&xt=urn:btih:$hex40"
            )
        )
    }

    @Test
    fun `extraction rejects non-torrent urls and invalid hashes`() {
        assertNull(extractInfoHashFromTorrentLink("https://example.com/$hex40.mkv"))
        assertNull(extractInfoHashFromTorrentLink("magnet:?dn=NoHashHere"))
        assertNull(extractInfoHashFromTorrentLink("torrent://null"))
        assertNull(extractInfoHashFromTorrentLink("torrent://nothexor32chars"))
        assertNull(extractInfoHashFromTorrentLink("magnet:?xt=urn:btih:tooshort"))
        assertNull(extractInfoHashFromTorrentLink(null))
        assertNull(extractInfoHashFromTorrentLink("   "))
    }
}
