package com.nuvio.tv.ui.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeroMetadataFormatterTest {

    @Test
    fun `formats hours and minutes`() {
        assertEquals("2h 5m", formatHeroRuntime("125"))
        assertEquals("1h 30m", formatHeroRuntime("90 min"))
        assertEquals("45m", formatHeroRuntime("45"))
    }

    @Test
    fun `drops the minute part when a runtime is whole hours`() {
        assertEquals("2h", formatHeroRuntime("120"))
    }

    /**
     * TMDB answers with 0, not null, for a title whose length it does not know yet. That used to
     * reach the screen as a literal "0m" beside the genre.
     */
    @Test
    fun `returns null for a zero runtime`() {
        assertNull(formatHeroRuntime("0"))
        assertNull(formatHeroRuntime("0 min"))
        assertNull(formatHeroRuntime("0h 0m"))
    }

    @Test
    fun `returns null for a missing runtime`() {
        assertNull(formatHeroRuntime(null))
        assertNull(formatHeroRuntime("   "))
    }

    @Test
    fun `passes through text it cannot read as minutes`() {
        assertEquals("unknown", formatHeroRuntime("unknown"))
    }
}
