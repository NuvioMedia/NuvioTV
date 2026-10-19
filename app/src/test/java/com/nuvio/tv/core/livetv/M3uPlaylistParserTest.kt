package com.nuvio.tv.core.livetv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class M3uPlaylistParserTest {
    @Test
    fun parsesQuotedMetadataAndCommasInChannelNames() {
        val result = M3uPlaylistParser.parse(
            """#EXTM3U
                |#EXTINF:-1 tvg-id="br.noticias" tvg-name="Jornal" tvg-logo="https://cdn.example/logo.png" group-title="Brasil",Jornal, São Paulo
                |https://stream.example/live?token=private
            """.trimMargin(),
            sourceId = "iptv-main",
        )

        assertEquals(1, result.channels.size)
        val channel = result.channels.single()
        assertEquals("Jornal, São Paulo", channel.displayName)
        assertEquals("br.noticias", channel.tvgId)
        assertEquals("Jornal", channel.tvgName)
        assertEquals("Brasil", channel.groupName)
        assertEquals("https://cdn.example/logo.png", channel.logoUrl)
        assertEquals("https://stream.example/live?token=private", channel.streamUrl)
        assertTrue(channel.id.startsWith("iptv-main:"))
    }

    @Test
    fun rejectsNonHttpSchemesAndCountsThemWithoutEchoingPlaylistData() {
        val result = M3uPlaylistParser.parse(
            """#EXTM3U
                |#EXTINF:-1,Local file
                |file:///private/video.ts
                |#EXTINF:-1,Script
                |javascript:alert(1)
            """.trimMargin(),
            sourceId = "user-source",
        )

        assertTrue(result.channels.isEmpty())
        assertEquals(2, result.skippedEntries)
    }

    @Test(expected = LiveTvPlaylistFormatException::class)
    fun requiresAnExtendedM3uHeader() {
        M3uPlaylistParser.parse("https://stream.example/live", sourceId = "user-source")
    }

    @Test
    fun duplicateStreamUrlsProduceOneChannel() {
        val result = M3uPlaylistParser.parse(
            """#EXTM3U
                |#EXTINF:-1,First
                |https://stream.example/live
                |#EXTINF:-1,Duplicate
                |https://stream.example/live
            """.trimMargin(),
            sourceId = "user-source",
        )
        assertEquals(1, result.channels.size)
        assertEquals(1, result.skippedEntries)
    }
}
