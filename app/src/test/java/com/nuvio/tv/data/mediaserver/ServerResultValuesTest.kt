package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.debrid.DebridStreamFormatter
import com.nuvio.tv.core.debrid.DebridStreamTemplateEngine
import com.nuvio.tv.domain.model.DebridSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerResultValuesTest {
    private val formatter = DebridStreamFormatter(DebridStreamTemplateEngine())

    private fun candidate(filename: String?, media: ServerMediaFacts = ServerMediaFacts()) = ServerCandidate(
        target = ServerPlaybackTarget(ServerItemRef("c1", "i1"), "s1"),
        title = "4K",
        description = "HEVC • TrueHD 7.1 • MKV",
        filename = filename,
        sizeBytes = 60L shl 30,
        media = media
    )

    private fun values(candidate: ServerCandidate) =
        ServerResultValues.of(candidate, "Jellyfin", "Home", "Home · Jellyfin", formatter::languageEmoji)

    @Test fun `a renamed file shows the formats the server read`() {
        val values = values(candidate("Movie Title (2024).mkv", ServerMediaFacts(
            resolution = 2160,
            videoCodec = "HEVC",
            labels = listOf("4K HEVC Dolby Vision", "English - Dolby TrueHD + Dolby Atmos - 7.1 - Default", "truehd", "eng",
                "Japanese - AAC - Stereo", "aac", "jpn"),
            bitrateKbps = 72_400
        )))
        assertEquals("Movie Title", values["stream.title"])
        assertEquals(2024, values["stream.year"])
        assertEquals("2160p", values["stream.resolution"])
        assertEquals("HEVC", values["stream.encode"])
        assertEquals(listOf("DV"), values["stream.visualTags"])
        assertEquals(listOf("TrueHD", "AAC", "Atmos"), values["stream.audioTags"])
        assertEquals(listOf("7.1"), values["stream.audioChannels"])
        assertEquals(listOf("en", "ja"), values["stream.languages"])
        assertEquals("72 Mbps", values["stream.bitrate"])
        assertEquals("Movie Title (2024).mkv", values["stream.filename"])
        assertEquals("Jellyfin", values["service.shortName"])
        assertEquals("Home", values["service.name"])
        assertEquals(true, values["service.cached"])
        assertNull(values["stream.indexer"])
        assertNull(values["stream.age"])
    }

    @Test fun `a release-named file keeps what its name says`() {
        val values = values(candidate("Movie.Title.2024.2160p.UHD.BluRay.REMUX.HDR10.HEVC.DTS-HD.MA.7.1-GROUP.mkv"))
        assertEquals("Movie Title", values["stream.title"])
        assertEquals("2160p", values["stream.resolution"])
        assertEquals("HEVC", values["stream.encode"])
        assertEquals(listOf("HDR10"), values["stream.visualTags"])
        assertEquals(listOf("DTS-HD MA"), values["stream.audioTags"])
        assertEquals("GROUP", values["stream.releaseGroup"])
        assertNull(values["stream.bitrate"])
    }

    @Test fun `the server's measurements take over from the name`() {
        val values = values(candidate("Movie.Title.2024.1080p.WEB-DL.x264-GROUP.mkv",
            ServerMediaFacts(resolution = 2160, videoCodec = "HEVC", bitrateKbps = 8_400)))
        assertEquals("2160p", values["stream.resolution"])
        assertEquals("HEVC", values["stream.encode"])
        assertEquals("8.4 Mbps", values["stream.bitrate"])
    }

    @Test fun `without a file name the result's title is read`() {
        assertEquals("2160p", values(candidate(null))["stream.resolution"])
    }

    @Test fun `the default templates show a server result`() {
        val (name, description) = formatter.render(DebridSettings(), values(candidate("Movie Title (2024).mkv",
            ServerMediaFacts(resolution = 2160, labels = listOf("English - Dolby TrueHD + Dolby Atmos - 7.1")))))
        assertEquals("4K Jellyfin Instant", name)
        assertTrue(description, description.lines().any { it == "Ready (Jellyfin)" })
        assertTrue(description, description.lines().any { "TrueHD" in it && "7.1" in it })
    }

    @Test fun `picture sizes and resolution labels give standard lines`() {
        assertEquals(2160, ServerMediaFacts.lines(3840, 1600))
        assertEquals(1080, ServerMediaFacts.lines(1920, 800))
        assertEquals(720, ServerMediaFacts.lines(1280, 536))
        assertEquals(576, ServerMediaFacts.lines(720, 576))
        assertNull(ServerMediaFacts.lines(null, null))
        assertEquals(2160, ServerMediaFacts.lines("4k"))
        assertEquals(1080, ServerMediaFacts.lines("1080"))
        assertEquals(720, ServerMediaFacts.lines("720p"))
        assertEquals(480, ServerMediaFacts.lines("sd"))
        assertNull(ServerMediaFacts.lines(""))
    }

    @Test fun `video codecs read as release names write them`() {
        assertEquals("HEVC", ServerMediaFacts.codecLabel("hevc"))
        assertEquals("AVC", ServerMediaFacts.codecLabel("h264"))
        assertEquals("AV1", ServerMediaFacts.codecLabel("av1"))
        assertEquals("VP9", ServerMediaFacts.codecLabel("vp9"))
        assertNull(ServerMediaFacts.codecLabel(""))
    }
}
