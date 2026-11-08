package com.nuvio.tv.core.party

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PartyStreamTest {
    private val stream = PartyStream(
        mediaKey = "movie|tt1||",
        url = "https://cdn.example.com/movie.mkv",
        headers = mapOf("Referer" to "https://example.com"),
        responseHeaders = mapOf("Content-Type" to "video/x-matroska"),
        name = "TB HD",
        description = "Movie 2017\n1.5 GB",
        addonName = "Torrentio",
        addonId = "addon:torrentio",
        addonLogo = "https://example.com/logo.png",
        filename = "movie.mkv",
        videoSize = 1_500_000_000L,
        bingeGroup = "torrentio|720p",
        badges = listOf(StreamBadge(name = "HDR", imageURL = "https://example.com/hdr.png")),
    )

    @Test
    fun remoteLinksCanBeShared() {
        assertTrue(isShareableStreamUrl("https://cdn.example.com/movie.mkv"))
        assertTrue(isShareableStreamUrl("http://192.168.1.20:8096/video"))
    }

    @Test
    fun localAndNonWebLinksCannotBeShared() {
        assertFalse(isShareableStreamUrl("http://127.0.0.1:11470/stream"))
        assertFalse(isShareableStreamUrl("http://localhost:8080/video"))
        assertFalse(isShareableStreamUrl("http://[::1]:8080/video"))
        assertFalse(isShareableStreamUrl("http://0.0.0.0/video"))
        assertFalse(isShareableStreamUrl("file:///data/movie.mkv"))
        assertFalse(isShareableStreamUrl("magnet:?xt=urn:btih:abc"))
    }

    @Test
    fun sharedStreamKeepsCardDetailsAndHeaders() {
        val item = stream.toStream()
        assertEquals("https://cdn.example.com/movie.mkv", item.getStreamUrl())
        assertEquals("TB HD", item.getDisplayName())
        assertEquals("Movie 2017\n1.5 GB", item.getDisplayDescription())
        assertEquals("Torrentio", item.addonName)
        assertEquals("https://example.com/logo.png", item.addonLogo)
        assertEquals(1_500_000_000L, item.behaviorHints?.videoSize)
        assertEquals("movie.mkv", item.behaviorHints?.filename)
        assertEquals(mapOf("Referer" to "https://example.com"), item.behaviorHints?.proxyHeaders?.request)
        assertEquals("HDR", item.badges.single().name)
        assertFalse(item.isTorrent())
    }

    @Test
    fun sharedStreamSurvivesTheWire() {
        val encoded = PartyApi.json.encodeToString(PartyStream.serializer(), stream)
        assertEquals(stream, PartyApi.json.decodeFromString(PartyStream.serializer(), encoded))
    }

    @Test
    fun mobileStreamPayloadDecodes() {
        val payload = """{"media_key":"movie|tt1||","url":"https://cdn.example.com/a.mkv","stream_type":"debrid","badges":[{"name":"4K","imageURL":"https://example.com/4k.png"}],"unknown":1}"""
        val decoded = PartyApi.json.decodeFromString(PartyStream.serializer(), payload)
        assertEquals("debrid", decoded.streamType)
        assertEquals("4K", decoded.badges.single().name)
    }

    @Test
    fun partySourceMatchesTorrentFileOrFilename() {
        val torrent = stream(infoHash = "ABCDEF", fileIdx = 2)
        assertTrue(PartySource(infoHash = "abcdef", fileIdx = 2).matches(torrent))
        assertFalse(PartySource(infoHash = "abcdef", fileIdx = 3).matches(torrent))
        assertTrue(PartySource(infoHash = "abcdef").matches(torrent))
        val direct = stream(filename = "Movie.2017.1080p.mkv")
        assertTrue(PartySource(filename = "movie.2017.1080p.mkv").matches(direct))
        assertFalse(PartySource(filename = "other.mkv").matches(direct))
        assertFalse(PartySource(name = "TB HD").matches(direct))
    }

    private fun stream(infoHash: String? = null, fileIdx: Int? = null, filename: String? = null) = Stream(
        name = "TB HD",
        title = null,
        description = null,
        url = if (infoHash == null) "https://cdn.example.com/a.mkv" else null,
        ytId = null,
        infoHash = infoHash,
        fileIdx = fileIdx,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            filename = filename,
        ),
        addonName = "Torrentio",
        addonLogo = null,
    )
}
