package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusScrollingTextTest {

    @Test
    fun `text that fits has nothing to scroll`() {
        assertEquals(0, focusScrollOverflowPx(fullHeightPx = 80, collapsedHeightPx = 80))
        assertEquals(0, focusScrollOverflowPx(fullHeightPx = 40, collapsedHeightPx = 80))
    }

    @Test
    fun `overflow is the part hidden past the clip`() {
        assertEquals(70, focusScrollOverflowPx(fullHeightPx = 150, collapsedHeightPx = 80))
    }

    @Test
    fun `duration follows distance at the given speed`() {
        assertEquals(2000, focusScrollDurationMillis(overflowPx = 100, pxPerSecond = 50f))
        assertEquals(4000, focusScrollDurationMillis(overflowPx = 200, pxPerSecond = 50f))
    }

    @Test
    fun `a longer description scrolls for longer at the same speed`() {
        val shorter = focusScrollDurationMillis(overflowPx = 60, pxPerSecond = 42f)
        val longer = focusScrollDurationMillis(overflowPx = 240, pxPerSecond = 42f)

        assertTrue(longer > shorter)
    }

    @Test
    fun `nothing to scroll means no animation`() {
        assertEquals(0, focusScrollDurationMillis(overflowPx = 0, pxPerSecond = 50f))
        assertEquals(0, focusScrollDurationMillis(overflowPx = -20, pxPerSecond = 50f))
    }

    @Test
    fun `a sub-pixel scroll still gets a positive duration`() {
        assertTrue(focusScrollDurationMillis(overflowPx = 1, pxPerSecond = 100_000f) >= 1)
    }

    @Test
    fun `a zero speed cannot produce an infinite animation`() {
        assertEquals(0, focusScrollDurationMillis(overflowPx = 100, pxPerSecond = 0f))
    }
}
