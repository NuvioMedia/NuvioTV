package com.nuvio.tv.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RuntimeParserTest {
    @Test
    fun `parses numeric and minute runtimes`() {
        assertEquals(118, parseRuntimeMinutes("118"))
        assertEquals(118, parseRuntimeMinutes("118 min"))
    }

    @Test
    fun `parses hour and minute runtimes`() {
        assertEquals(125, parseRuntimeMinutes("2h 5m"))
        assertEquals(90, parseRuntimeMinutes("1 hr 30 min"))
    }

    @Test
    fun `returns null for missing runtime`() {
        assertNull(parseRuntimeMinutes(null))
        assertNull(parseRuntimeMinutes(" "))
    }
}
