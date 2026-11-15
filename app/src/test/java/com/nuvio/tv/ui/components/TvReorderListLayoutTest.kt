package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class TvReorderListLayoutTest {

    private val rowHeight = 100f
    private val spacing = 10f

    @Test
    fun `empty slot count returns empty range`() {
        val range = visibleReorderSlotRange(
            slotCount = 0,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 0,
        )
        assertTrue(range.isEmpty())
    }

    @Test
    fun `top of list measures only rows covering viewport plus overscan`() {
        // stride = 110, viewport = 500 -> overscan covers ~9-10 slots around the fold
        val range = visibleReorderSlotRange(
            slotCount = 100,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 0,
        )
        assertEquals(0, range.first)
        // bottomEdge = 0 + 500 + 500 = 1000 -> last = 1000/110 = 9
        assertEquals(9, range.last)
        assertTrue(range.last < 99)
    }

    @Test
    fun `scrolled down does not measure rows above viewport`() {
        // scrollPx = 5000 -> content offset ~45 slots down; must not start at 0
        val range = visibleReorderSlotRange(
            slotCount = 100,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 5000,
        )
        assertTrue("first=${range.first} should skip off-screen rows", range.first > 20)
        assertTrue(range.last <= 99)
    }

    @Test
    fun `header height shifts the visible window`() {
        val withoutHeader = visibleReorderSlotRange(
            slotCount = 100,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 1000,
        )
        val withHeader = visibleReorderSlotRange(
            slotCount = 100,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 300f,
            scrollPx = 1000,
        )
        // header consumes 300px of the scroll content -> window starts earlier in slot space
        assertTrue(withHeader.first <= withoutHeader.first)
    }

    @Test
    fun `range is clamped to slot bounds near the end`() {
        val range = visibleReorderSlotRange(
            slotCount = 20,
            rowHeightPx = rowHeight,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 100000,
        )
        assertEquals(19, range.last)
        assertTrue(range.first <= range.last)
    }

    @Test
    fun `degenerate height falls back to measuring everything once`() {
        val range = visibleReorderSlotRange(
            slotCount = 10,
            rowHeightPx = 0f,
            rowSpacingPx = spacing,
            viewportHeightPx = 500f,
            headerHeightPx = 0f,
            scrollPx = 0,
        )
        assertEquals(0..9, range)
    }

    @Test
    fun `slot tops accumulate variable row heights plus spacing`() {
        val heights = floatArrayOf(100f, 140f, 100f)
        val tops = computeSlotTops(
            slotCount = 3,
            heightAt = { heights[it] },
            spacingPx = 10f,
        )
        assertEquals(0f, tops[0], 0.001f)
        assertEquals(110f, tops[1], 0.001f)
        assertEquals(260f, tops[2], 0.001f)
    }

    @Test
    fun `slot tops with uniform heights match stride math`() {
        val tops = computeSlotTops(
            slotCount = 5,
            heightAt = { 100f },
            spacingPx = 10f,
        )
        for (i in 0 until 5) {
            assertEquals(i * 110f, tops[i], 0.001f)
        }
    }

    @Test
    fun `slot tops empty when no slots`() {
        val tops = computeSlotTops(0, { 100f }, 10f)
        assertEquals(0, tops.size)
    }

    private fun assertTrue(condition: Boolean) {
        org.junit.Assert.assertTrue(condition)
    }

    private fun assertTrue(message: String, condition: Boolean) {
        org.junit.Assert.assertTrue(message, condition)
    }
}
