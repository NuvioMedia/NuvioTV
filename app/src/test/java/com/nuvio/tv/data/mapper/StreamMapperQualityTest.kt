package com.nuvio.tv.data.mapper

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamMapperQualityTest {

    @Test
    fun `detects 4k from addon stream name`() {
        val result = detectQuality("TestAddon 4K")
        assertEquals(2160, result?.second)
    }

    @Test
    fun `detects 1080p from addon stream name`() {
        val result = detectQuality("TestAddon 1080p")
        assertEquals(1080, result?.second)
    }

    @Test
    fun `detects 360p from addon stream name`() {
        val result = detectQuality("TestAddon 360p")
        assertEquals(360, result?.second)
    }

    @Test
    fun `returns null when no resolution mentioned`() {
        assertNull(detectQuality("TestAddon"))
    }
}
