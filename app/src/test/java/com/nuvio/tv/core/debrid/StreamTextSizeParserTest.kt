package com.nuvio.tv.core.debrid

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamTextSizeParserTest {

    @Test
    fun `parses torrentio style size line`() {
        val torrentioTitle = "Movie.Name.2024.1080p.WEB-DL\n👤 12 💾 1.81 GB ⚙️ ThePirateBay"
        assertEquals(
            (1.81 * 1024 * 1024 * 1024).toLong(),
            StreamTextSizeParser.sizeBytesFromText(torrentioTitle)
        )
    }

    @Test
    fun `parses plain sizes across units`() {
        assertEquals(700L * 1024 * 1024, StreamTextSizeParser.sizeBytesFromText("700 MB"))
        assertEquals(
            (1.5 * 1024 * 1024 * 1024 * 1024).toLong(),
            StreamTextSizeParser.sizeBytesFromText("1.5 TB")
        )
        assertEquals(512L * 1024, StreamTextSizeParser.sizeBytesFromText("512KB"))
    }

    @Test
    fun `parses comma decimal separator`() {
        assertEquals(
            (2.4 * 1024 * 1024 * 1024).toLong(),
            StreamTextSizeParser.sizeBytesFromText("💾 2,4 GB")
        )
    }

    @Test
    fun `does not misread resolution year or bitrate tokens`() {
        assertNull(StreamTextSizeParser.sizeBytesFromText("Movie.2024.2160p.x265.10bit"))
        assertNull(StreamTextSizeParser.sizeBytesFromText("audio 320kbps AAC"))
        assertNull(StreamTextSizeParser.sizeBytesFromText(null))
        assertNull(StreamTextSizeParser.sizeBytesFromText("  "))
    }

    @Test
    fun `stream text lookup prefers description then title then name`() {
        val stream = sampleStream(
            name = "Torrentio 4k",
            title = "Movie\n💾 2 GB",
            description = "💾 1 GB"
        )
        assertEquals(1024L * 1024 * 1024, StreamTextSizeParser.sizeBytesFromStreamText(stream))

        val titleOnly = sampleStream(name = "Torrentio 4k", title = "Movie\n💾 2 GB", description = null)
        assertEquals(2048L * 1024 * 1024, StreamTextSizeParser.sizeBytesFromStreamText(titleOnly))
    }

    @Test
    fun `effective size prefers structured fields over text parsing`() {
        val structured = sampleStream(
            name = null,
            title = "Movie\n💾 2 GB",
            description = null
        ).copy(
            behaviorHints = StreamBehaviorHints(
                notWebReady = null,
                bingeGroup = null,
                countryWhitelist = null,
                proxyHeaders = null,
                videoSize = 123L
            )
        )
        assertEquals(123L, StreamTextSizeParser.effectiveSizeBytes(structured))

        val textOnly = sampleStream(name = null, title = "Movie\n💾 2 GB", description = null)
        assertEquals(2048L * 1024 * 1024, StreamTextSizeParser.effectiveSizeBytes(textOnly))
    }

    private fun sampleStream(name: String?, title: String?, description: String?): Stream = Stream(
        name = name,
        title = title,
        description = description,
        url = null,
        ytId = null,
        infoHash = "abc",
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = "Torrentio",
        addonLogo = null
    )
}
