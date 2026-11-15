package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TvReorderListLayoutTest {

    private val rowHeight = 100f
    private val spacing = 10f

    private fun uniformTops(count: Int, height: Float = rowHeight, gap: Float = spacing): FloatArray {
        return computeSlotTops(count, { height }, gap)
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

    @Test
    fun `empty tops returns empty range`() {
        val range = visibleSlotRangeFromTops(
            tops = FloatArray(0),
            topEdgePx = 0f,
            bottomEdgePx = 500f,
        )
        assertTrue(range.isEmpty())
    }

    @Test
    fun `tops range at scroll top covers viewport plus overscan`() {
        // stride 110, tops 0..; window [-500, 1000] -> slots 0..9
        val tops = uniformTops(100)
        val range = visibleSlotRangeFromTops(tops, topEdgePx = -500f, bottomEdgePx = 1000f)
        assertEquals(0, range.first)
        assertEquals(9, range.last)
        assertTrue(range.last < 99)
    }

    @Test
    fun `tops range skips off-screen rows when scrolled`() {
        // tops[i] = i*110; window [4500, 5500] -> slots ~41..50
        val tops = uniformTops(100)
        val range = visibleSlotRangeFromTops(tops, topEdgePx = 4500f, bottomEdgePx = 5500f)
        assertTrue("first=${range.first}", range.first in 40..42)
        assertTrue(range.last in 49..51)
    }

    @Test
    fun `tops range follows variable heights not uniform stride`() {
        // first 40 rows are 200px tall, rest 100px -> cumulative tops diverge from stride math
        val heights = FloatArray(100) { if (it < 40) 200f else 100f }
        val tops = computeSlotTops(100, { heights[it] }, 10f)
        // window around content y = 40*210 = 8400 -> slot ~42; stride-based estimate would say ~76
        val range = visibleSlotRangeFromTops(tops, topEdgePx = 8400f - 500f, bottomEdgePx = 8400f + 500f)
        assertTrue("first=${range.first} should be near slot 38", range.first in 36..38)
    }

    @Test
    fun `tops range clamps to bounds near end`() {
        val tops = uniformTops(20)
        val range = visibleSlotRangeFromTops(tops, topEdgePx = 5000f, bottomEdgePx = 100000f)
        assertEquals(19, range.last)
        assertTrue(range.first <= range.last)
    }
}
