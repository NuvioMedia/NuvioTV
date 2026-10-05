package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class InAppYouTubeExtractorSourcePreferenceTest {

    private val extractor = InAppYouTubeExtractor()

    @Test
    fun `trailers try separate video and audio first, then HLS, then progressive`() {
        assertEquals(
            listOf(PlaybackSourceKind.ADAPTIVE, PlaybackSourceKind.HLS_MANIFEST, PlaybackSourceKind.PROGRESSIVE),
            extractor.sourcePreference(singleUrl = false)
        )
    }

    @Test
    fun `a single url prefers the HLS manifest, then progressive`() {
        assertEquals(
            listOf(PlaybackSourceKind.HLS_MANIFEST, PlaybackSourceKind.PROGRESSIVE),
            extractor.sourcePreference(singleUrl = true)
        )
    }

    @Test
    fun `a single url never uses the adaptive formats, whose audio is a separate file`() {
        assertFalse(PlaybackSourceKind.ADAPTIVE in extractor.sourcePreference(singleUrl = true))
    }
}
