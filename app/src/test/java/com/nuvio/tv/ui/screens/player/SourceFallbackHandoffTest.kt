package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SourceFallbackHandoffTest {
    private val tried = SourceFallbackHandoff.TriedSources(keys = setOf("url:https://a", "url:https://b"), attempts = 1)

    @Test
    fun `the playback the sources were tried for picks them up once`() {
        SourceFallbackHandoff.set("https://b", tried)

        assertEquals(tried, SourceFallbackHandoff.take("https://b"))
        assertNull(SourceFallbackHandoff.take("https://b"))
    }

    @Test
    fun `the URL the player normalized finds them too`() {
        SourceFallbackHandoff.set("https://b", tried)

        assertEquals(tried, SourceFallbackHandoff.take("https://b?raw", "https://b"))
    }

    @Test
    fun `another playback gets nothing and drops the leftover`() {
        SourceFallbackHandoff.set("https://b", tried)

        assertNull(SourceFallbackHandoff.take("https://c"))
        assertNull(SourceFallbackHandoff.take("https://b"))
    }

    @Test
    fun `a playback without a URL hands nothing over`() {
        SourceFallbackHandoff.set(null, tried)

        assertNull(SourceFallbackHandoff.take(""))
    }
}
