package com.nuvio.tv.ui.screens.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class MpvUserConfigOptionCountTest {
    @Test
    fun `blank config has no options`() {
        assertEquals(0, mpvUserConfigOptionCount(""))
        assertEquals(0, mpvUserConfigOptionCount("  \n\n "))
    }

    @Test
    fun `comments and profile headers are not counted`() {
        val config = """
            # comment
            deband=yes
              scale=spline36

            [anime]
            deband-iterations=2
        """.trimIndent()

        assertEquals(3, mpvUserConfigOptionCount(config))
    }
}
