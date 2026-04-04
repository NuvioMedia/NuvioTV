package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import org.junit.Assert.assertEquals
import org.junit.Test

class RecoverySourceCandidateTest {

    @Test
    fun `buildCandidateStreamSources continues with next same-addon stream order`() {
        val first = stream(
            addonName = "AddonA",
            name = "First",
            url = "https://example.com/first.m3u8"
        )
        val second = stream(
            addonName = "AddonA",
            name = "Second",
            url = "https://example.com/second.m3u8",
            sources = listOf("https://example.com/second-backup.m3u8"),
            filename = "second.mkv",
            videoHash = "hash-2"
        )
        val third = stream(
            addonName = "AddonA",
            name = "Third",
            url = "https://example.com/third.m3u8",
            videoHash = "hash-3"
        )
        val otherAddon = stream(
            addonName = "AddonB",
            name = "Other",
            url = "https://example.com/other.m3u8"
        )

        val candidates = buildCandidateStreamSources(
            stream = second,
            selectedUrl = "https://example.com/second-backup.m3u8",
            allStreamsContext = listOf(first, second, third, otherAddon),
            fallbackFilename = "fallback.mp4"
        )

        assertEquals(
            listOf(
                "https://example.com/second-backup.m3u8",
                "https://example.com/second.m3u8",
                "https://example.com/third.m3u8",
                "https://example.com/first.m3u8"
            ),
            candidates.map { it.url }
        )
        assertEquals(
            listOf("Second", "Second", "Third", "First"),
            candidates.map { it.streamName }
        )
        assertEquals(
            listOf("second.mkv", "second.mkv", "fallback.mp4", "fallback.mp4"),
            candidates.map { it.filename }
        )
        assertEquals(
            listOf("hash-2", "hash-2", "hash-3", null),
            candidates.map { it.videoHash }
        )
    }

    private fun stream(
        addonName: String,
        name: String,
        url: String,
        sources: List<String>? = null,
        filename: String? = null,
        videoHash: String? = null
    ): Stream = Stream(
        name = name,
        title = null,
        description = null,
        url = url,
        sources = sources,
        ytId = null,
        infoHash = null,
        fileIdx = null,
        externalUrl = null,
        behaviorHints = StreamBehaviorHints(
            notWebReady = null,
            bingeGroup = null,
            countryWhitelist = null,
            proxyHeaders = null,
            videoHash = videoHash,
            videoSize = null,
            filename = filename
        ),
        addonName = addonName,
        addonLogo = null
    )
}
