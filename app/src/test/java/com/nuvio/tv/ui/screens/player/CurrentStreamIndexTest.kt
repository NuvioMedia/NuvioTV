package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentStreamIndexTest {
    private fun stream(
        name: String? = "1080P WEB-DL ★★★★★",
        description: String? = "Release A",
        url: String? = "https://addon.example/new-token",
        infoHash: String? = null,
        addonName: String = "AIOStreams"
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = null,
        addonName = addonName,
        addonLogo = null
    )

    private fun find(
        streams: List<Stream>,
        infoHash: String? = null,
        addonName: String? = "AIOStreams",
        url: String? = "https://addon.example/old-token",
        name: String? = "1080P WEB-DL ★★★★★",
        description: String? = "Release A"
    ) = findCurrentStreamIndex(
        streams = streams,
        currentStreamInfoHash = infoHash,
        currentStreamFileIdx = null,
        currentStreamAddonName = addonName,
        currentStreamUrl = url,
        currentStreamName = name,
        currentStreamDescription = description
    )

    @Test
    fun `url match wins over name match`() {
        val streams = listOf(
            stream(),
            stream(description = "Release B", url = "https://addon.example/old-token")
        )

        assertEquals(1, find(streams))
    }

    @Test
    fun `falls back to name when addon issued a new url`() {
        assertEquals(1, find(listOf(stream(name = "720P"), stream())))
    }

    @Test
    fun `description separates streams sharing a template name`() {
        val streams = listOf(stream(description = "Release B"), stream())

        assertEquals(1, find(streams))
    }

    @Test
    fun `ambiguous name without description marks nothing`() {
        val streams = listOf(stream(description = "Release B"), stream())

        assertEquals(-1, find(streams, description = null))
    }

    @Test
    fun `identical streams mark nothing`() {
        assertEquals(-1, find(listOf(stream(), stream())))
    }

    @Test
    fun `old cache entry without addon and description matches a unique name`() {
        val streams = listOf(stream(name = "720P"), stream())

        assertEquals(1, find(streams, addonName = null, description = null))
    }

    @Test
    fun `same name from another addon does not match`() {
        assertEquals(-1, find(listOf(stream(addonName = "Torrentio"))))
    }

    @Test
    fun `unnamed stream is matched by addon name`() {
        val streams = listOf(stream(name = null, addonName = "Comet"))

        assertEquals(0, find(streams, addonName = "Comet", name = "Comet"))
    }
}
