package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayerAspectScaleUtilsTest {

    @Test
    fun `is4kDolbyOrHevc returns true for 4K Dolby Vision`() {
        val result = is4kDolbyOrHevc(
            width = 3840,
            height = 2160,
            mime = "video/dolby-vision",
            codecs = "dvh1.05.01"
        )
        assertTrue(result)
    }

    @Test
    fun `is4kDolbyOrHevc returns true for 4K HEVC`() {
        val result = is4kDolbyOrHevc(
            width = 3840,
            height = 2080,
            mime = "video/hevc",
            codecs = "hvc1.1.6.L153.B0"
        )
        assertTrue(result)
    }

    @Test
    fun `is4kDolbyOrHevc returns false if resolution is below 4K`() {
        val result = is4kDolbyOrHevc(
            width = 1920,
            height = 1080,
            mime = "video/dolby-vision",
            codecs = "dvh1.05.01"
        )
        assertFalse(result)
    }

    @Test
    fun `is4kDolbyOrHevc returns false if format is not Dolby Vision or HEVC`() {
        val result = is4kDolbyOrHevc(
            width = 3840,
            height = 2160,
            mime = "video/avc",
            codecs = "avc1.64002a"
        )
        assertFalse(result)
    }
}
