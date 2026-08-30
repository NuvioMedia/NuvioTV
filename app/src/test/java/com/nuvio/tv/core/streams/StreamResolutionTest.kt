package com.nuvio.tv.core.streams

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StreamResolutionTest {

    @Test
    fun `detects explicit heights`() {
        assertEquals(2160, StreamResolution.detect("TestAddon 2160p"))
        assertEquals(1440, StreamResolution.detect("TestAddon 1440p"))
        assertEquals(1080, StreamResolution.detect("TestAddon 1080p"))
        assertEquals(720, StreamResolution.detect("TestAddon 720p"))
        assertEquals(360, StreamResolution.detect("TestAddon 360p"))
    }

    @Test
    fun `detects release name aliases`() {
        assertEquals(2160, StreamResolution.detect("TestAddon 4K"))
        assertEquals(2160, StreamResolution.detect("TestAddon UHD"))
        assertEquals(1440, StreamResolution.detect("TestAddon 2K"))
        assertEquals(1080, StreamResolution.detect("TestAddon FHD"))
        assertEquals(720, StreamResolution.detect("TestAddon HD"))
        assertEquals(480, StreamResolution.detect("TestAddon SD"))
    }

    @Test
    fun `requires token boundaries so encodes are not resolutions`() {
        assertNull(StreamResolution.detect("TestAddon HDTV"))
        assertNull(StreamResolution.detect("TestAddon HDRip"))
        assertNull(StreamResolution.detect("TestAddon x264"))
    }

    @Test
    fun `detects heights without a dedicated alias`() {
        assertEquals(800, StreamResolution.detect("TestAddon 800p"))
        assertEquals(540, StreamResolution.detect("TestAddon 540p"))
    }

    @Test
    fun `accepts bare numeric labels only when they are the whole value`() {
        assertEquals(800, StreamResolution.detect("800"))
        assertNull(StreamResolution.detect("Obsession 2026"))
    }

    @Test
    fun `earlier labels win over later ones`() {
        assertEquals(720, StreamResolution.detect("TestAddon 720p", "Also available in 1080p"))
    }

    @Test
    fun `falls through to the next label when one carries no resolution`() {
        assertEquals(1080, StreamResolution.detect("TestAddon", "TestAddon 1080p"))
    }

    @Test
    fun `highest advertised token wins inside a single label`() {
        assertEquals(2160, StreamResolution.detect("TestAddon 4K HDR 1080p downscale"))
    }

    @Test
    fun `returns null without a resolution`() {
        assertNull(StreamResolution.detect("TestAddon"))
        assertNull(StreamResolution.detect(null))
        assertNull(StreamResolution.detect("   "))
    }
}
