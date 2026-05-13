package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class AutoplaySessionCountTest {

    @Test
    fun `manual selection resets count to zero`() {
        assertEquals(0, nextConsecutiveAutoPlayCount(currentCount = 5, isAutoPlay = false))
    }

    @Test
    fun `autoplay increments count`() {
        assertEquals(4, nextConsecutiveAutoPlayCount(currentCount = 3, isAutoPlay = true))
    }

    @Test
    fun `first autoplay from zero increments to one`() {
        assertEquals(1, nextConsecutiveAutoPlayCount(currentCount = 0, isAutoPlay = true))
    }
}
