package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.dto.StreamDto
import com.squareup.moshi.Moshi
import org.junit.Assert.*
import org.junit.Test

class UsenetStreamProtocolTest {
    @Test fun `Stremio Usenet fields survive Moshi and qualify for autoplay`() {
        val dto = requireNotNull(Moshi.Builder().build().adapter(StreamDto::class.java).fromJson("""
            {"name":"AIOStreams","nzbUrl":"https://indexer.example/release.nzb",
             "servers":["nntps://user:password@news.example:563/80"],
             "fileIdx":2,"fileMustInclude":"/.mkv$/i","behaviorHints":{"filename":"Show.S01E02.mkv"}}
        """.trimIndent()))
        val stream = dto.toDomain("AIOStreams", null)
        assertTrue(stream.isUsenet())
        assertFalse(stream.isTorrent())
        assertFalse(stream.isExternal())
        assertEquals(2, stream.fileIdx)
        assertEquals("/.mkv$/i", stream.fileMustInclude)
        assertEquals(1, stream.servers?.size)
        assertEquals(stream, StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream), mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "", source = StreamAutoPlaySource.ALL_SOURCES,
            installedAddonNames = setOf("AIOStreams"), selectedAddons = emptySet(), selectedPlugins = emptySet(),
            preferredBingeGroup = null, preferBingeGroupInSelection = false
        ))
    }
}
