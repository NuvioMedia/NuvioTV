package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class CurrentStreamIndexTest {
    private fun stream(
        name: String? = "1080P WEB-DL ★★★★★",
        description: String? = "Release A",
        url: String? = "https://addon.example/new-token",
        infoHash: String? = null,
        addonName: String = "AIOStreams",
        filename: String? = null
    ) = Stream(
        name = name,
        title = null,
        description = description,
        url = url,
        ytId = null,
        infoHash = infoHash,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = filename?.let { hints(it) },
        addonName = addonName,
        addonLogo = null
    )

    private fun find(
        streams: List<Stream>,
        infoHash: String? = null,
        addonName: String? = "AIOStreams",
        url: String? = "https://addon.example/old-token",
        name: String? = "1080P WEB-DL ★★★★★",
        description: String? = "Release A",
        filename: String? = null
    ) = findCurrentStreamIndex(
        streams = streams,
        currentStreamInfoHash = infoHash,
        currentStreamFileIdx = null,
        currentStreamAddonName = addonName,
        currentStreamUrl = url,
        currentStreamName = name,
        currentStreamDescription = description,
        currentFilename = filename
    )

    private fun hints(filename: String) = StreamBehaviorHints(
        notWebReady = null,
        bingeGroup = null,
        countryWhitelist = null,
        proxyHeaders = null,
        videoHash = null,
        videoSize = null,
        filename = filename
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
    fun `filename separates streams with identical name and description`() {
        val streams = listOf(stream(filename = "a.mkv"), stream(filename = "b.mkv"))

        assertEquals(1, find(streams, filename = "b.mkv"))
    }

    @Test
    fun `filename wins over a different template and description`() {
        val streams = listOf(
            stream(),
            stream(name = "Changed", description = "Changed", filename = "a.mkv")
        )

        assertEquals(1, find(streams, filename = "a.mkv"))
    }

    @Test
    fun `filename from another addon does not match`() {
        val streams = listOf(stream(name = "720P", addonName = "Torrentio", filename = "a.mkv"))

        assertEquals(-1, find(streams, filename = "a.mkv"))
    }

    @Test
    fun `duplicate filename falls through to name and description`() {
        val streams = listOf(
            stream(description = "Release B", filename = "a.mkv"),
            stream(filename = "a.mkv")
        )

        assertEquals(1, find(streams, filename = "a.mkv"))
    }

    @Test
    fun `unnamed stream is matched by addon name`() {
        val streams = listOf(stream(name = null, addonName = "Comet"))

        assertEquals(0, find(streams, addonName = "Comet", name = "Comet"))
    }
}
