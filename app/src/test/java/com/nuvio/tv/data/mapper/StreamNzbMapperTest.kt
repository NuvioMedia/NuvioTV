package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.StreamArchiveSourceDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.domain.model.StreamSourceType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamNzbMapperTest {
    @Test
    fun `maps native Stremio NZB and archive fields`() {
        val domain = StreamDto(
            name = "Usenet",
            nzbUrl = "https://indexer.example/release.nzb",
            servers = listOf("nntps://user:password@news.example.com:563/20"),
            fileMustInclude = "/\\.mkv$/i",
            rarUrls = listOf(StreamArchiveSourceDto("https://cdn.example/part01.rar", 1024)),
            sevenZipUrls = listOf(StreamArchiveSourceDto("https://cdn.example/release.7z"))
        ).toDomain(addonName = "Test addon", addonLogo = null)

        assertEquals("https://indexer.example/release.nzb", domain.nzbUrl)
        assertEquals("/\\.mkv$/i", domain.fileMustInclude)
        assertEquals(1024L, domain.rarUrls?.single()?.bytes)
        assertEquals("https://cdn.example/release.7z", domain.sevenZipUrls?.single()?.url)
        assertTrue(domain.hasNntpServers())
        assertTrue(domain.hasArchiveSource())
        assertEquals(StreamSourceType.NZB, domain.sourceType())
    }

    @Test
    fun `stable key identifies NZB without exposing provider credentials`() {
        val dto = StreamDto(
            name = "Usenet",
            nzbUrl = "https://indexer.example/release.nzb",
            servers = listOf("nntps://secret-user:secret-password@news.example.com:563/20")
        )
        val domain = dto.toDomain(addonName = "Test addon", addonLogo = null)

        val key = domain.stableKey()
        assertTrue(key.contains("https://indexer.example/release.nzb"))
        assertFalse(key.contains("secret-user"))
        assertFalse(key.contains("secret-password"))
        assertFalse(domain.toString().contains("secret-user"))
        assertFalse(domain.toString().contains("secret-password"))
        assertFalse(dto.toString().contains("secret-user"))
        assertFalse(dto.toString().contains("secret-password"))
    }
}
