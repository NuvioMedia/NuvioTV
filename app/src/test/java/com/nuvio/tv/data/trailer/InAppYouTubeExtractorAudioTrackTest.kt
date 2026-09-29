package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Test

class InAppYouTubeExtractorAudioTrackTest {

    @Test
    fun `default audio track is preferred over a higher-bitrate alternate-language dub`() {
        val extractor = InAppYouTubeExtractor()
        val englishDefault = audioCandidate(url = "english", score = 64_000.0, isDefaultAudioTrack = true)
        val higherBitrateDub = audioCandidate(url = "french-dub", score = 128_000.0, isDefaultAudioTrack = false)

        val ranked = extractor.sortCandidates(listOf(higherBitrateDub, englishDefault))

        assertEquals(listOf("english", "french-dub"), ranked.map { it.url })
    }

    @Test
    fun `single-track audio with no audioTrack flag is treated as default`() {
        val extractor = InAppYouTubeExtractor()
        val onlyTrack = audioCandidate(url = "only-track", score = 64_000.0, isDefaultAudioTrack = true)

        val ranked = extractor.sortCandidates(listOf(onlyTrack))

        assertEquals(listOf("only-track"), ranked.map { it.url })
    }

    private fun audioCandidate(
        url: String,
        score: Double,
        isDefaultAudioTrack: Boolean
    ): StreamCandidate {
        return StreamCandidate(
            client = "android",
            priority = 1,
            url = url,
            score = score,
            hasN = false,
            itag = "140",
            height = 0,
            fps = 0,
            ext = "m4a",
            isDefaultAudioTrack = isDefaultAudioTrack
        )
    }
}
