package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Test

class InAppYouTubeExtractorVideoCapTest {

    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `cap drops streams above 1080p`() {
        val streams = listOf(
            videoCandidate(url = "2160p", width = 3840, height = 2160),
            videoCandidate(url = "1080p", width = 1920, height = 1080)
        )

        val capped = extractor.capVideoHeight(streams, maxHeight = 1080)

        assertEquals(listOf("1080p"), capped.map { it.url })
    }

    @Test
    fun `cap uses width for widescreen trailers`() {
        val streams = listOf(
            videoCandidate(url = "1440p-scope", width = 2560, height = 1072),
            videoCandidate(url = "1080p-scope", width = 1920, height = 804)
        )

        val capped = extractor.capVideoHeight(streams, maxHeight = 1080)

        assertEquals(listOf("1080p-scope"), capped.map { it.url })
    }

    @Test
    fun `cap keeps every stream when none fits`() {
        val streams = listOf(videoCandidate(url = "2160p", width = 3840, height = 2160))

        val capped = extractor.capVideoHeight(streams, maxHeight = 1080)

        assertEquals(listOf("2160p"), capped.map { it.url })
    }

    @Test
    fun `no cap keeps 4K`() {
        val streams = listOf(
            videoCandidate(url = "2160p", width = 3840, height = 2160),
            videoCandidate(url = "1080p", width = 1920, height = 1080)
        )

        val capped = extractor.capVideoHeight(streams, maxHeight = Int.MAX_VALUE)

        assertEquals(listOf("2160p", "1080p"), capped.map { it.url })
    }

    private fun videoCandidate(url: String, width: Int, height: Int): StreamCandidate {
        return StreamCandidate(
            client = "visionos",
            priority = 0,
            url = url,
            score = height.toDouble(),
            hasN = false,
            itag = "0",
            height = height,
            fps = 30,
            ext = "webm",
            width = width
        )
    }
}
