package com.nuvio.tv.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
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

    @Test
    fun `row cache reuses content when item moving state and callbacks are unchanged`() {
        val cache = TvReorderRowCache<String, Any>()
        val itemContent = Any()
        val canMove = Any()
        val onStartMove = Any()
        val onConfirmMove = Any()
        var factoryCalls = 0

        val first = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) {
            factoryCalls++
            Any()
        }
        val second = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) {
            factoryCalls++
            Any()
        }

        assertEquals(1, factoryCalls)
        assertSame(first, second)
    }

    @Test
    fun `row cache invalidates when itemContent callback changes even if item and moving flag match`() {
        val cache = TvReorderRowCache<String, Any>()
        val canMove = Any()
        val onStartMove = Any()
        val onConfirmMove = Any()
        val initialRenderer = Any()
        val updatedRenderer = Any()

        val first = cache.getOrPut(
            key = "row-b",
            item = "B",
            isMoving = false,
            itemContent = initialRenderer,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        val second = cache.getOrPut(
            key = "row-b",
            item = "B",
            isMoving = false,
            itemContent = updatedRenderer,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        assertNotSame(first, second)
    }

    @Test
    fun `row cache invalidates when canMove or action callbacks change`() {
        val cache = TvReorderRowCache<String, Any>()
        val itemContent = Any()
        val onStartMove = Any()
        val onConfirmMove = Any()

        val first = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = Any(),
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        val second = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = Any(),
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        assertNotSame(first, second)
    }

    @Test
    fun `row cache retainKeys evicts removed rows`() {
        val cache = TvReorderRowCache<String, Any>()
        val itemContent = Any()
        val canMove = Any()
        val onStartMove = Any()
        val onConfirmMove = Any()

        val first = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        cache.retainKeys(setOf("row-b"))

        val recreated = cache.getOrPut(
            key = "row-a",
            item = "A",
            isMoving = false,
            itemContent = itemContent,
            canMove = canMove,
            onStartMove = onStartMove,
            onConfirmMove = onConfirmMove,
        ) { Any() }

        assertNotSame(first, recreated)
    }

    @Test
    fun `cancel scroll restores initial scroll position when cancelled slot stays visible under tall header`() {
        val target = computeCancelScrollTargetPx(
            slotTopPx = 0f,
            slotHeightPx = 100f,
            headerOffsetPx = 1200f,
            viewportHeightPx = 1000f,
            initialScrollPx = 900,
            maxScrollPx = 3000,
        )
        assertEquals(900, target)
    }

    @Test
    fun `cancel scroll accounts for tall header when initial scroll is missing or out of view`() {
        val target = computeCancelScrollTargetPx(
            slotTopPx = 0f,
            slotHeightPx = 100f,
            headerOffsetPx = 1200f,
            viewportHeightPx = 1000f,
            initialScrollPx = 0,
            maxScrollPx = 3000,
        )
        // Slot occupies [1200, 1300]; scrolling to 0 would push it off-screen below viewport 1000.
        assertTrue("target=$target should keep [1200, 1300] inside viewport", target in 300..1200)
        assertEquals(1100, target)
    }

    @Test
    fun `cancel scroll shows short header when top slot fits inside viewport from scroll zero`() {
        val target = computeCancelScrollTargetPx(
            slotTopPx = 0f,
            slotHeightPx = 100f,
            headerOffsetPx = 200f,
            viewportHeightPx = 1000f,
            initialScrollPx = null,
            maxScrollPx = 3000,
        )
        assertEquals(0, target)
    }

    @Test
    fun `cancel scroll clamps to maxScrollPx`() {
        val target = computeCancelScrollTargetPx(
            slotTopPx = 2500f,
            slotHeightPx = 100f,
            headerOffsetPx = 500f,
            viewportHeightPx = 1000f,
            initialScrollPx = null,
            maxScrollPx = 2200,
        )
        assertEquals(2200, target)
    }

    @Test
    fun `anchored header scroll grows by header delta to keep list stationary`() {
        val target = computeAnchoredHeaderScrollPx(
            preMeasureScrollPx = 700,
            headerDeltaPx = 200,
            maxScrollPx = 900,
        )
        assertEquals(900, target)
    }

    @Test
    fun `anchored header scroll shrinks by header delta from pre-clamp scroll`() {
        val targetAtBottom = computeAnchoredHeaderScrollPx(
            preMeasureScrollPx = 900,
            headerDeltaPx = -200,
            maxScrollPx = 700,
        )
        assertEquals(700, targetAtBottom)

        val targetMidList = computeAnchoredHeaderScrollPx(
            preMeasureScrollPx = 500,
            headerDeltaPx = -200,
            maxScrollPx = 700,
        )
        assertEquals(300, targetMidList)
    }

    @Test
    fun `anchored header scroll clamps to zero and maxScrollPx`() {
        assertEquals(
            0,
            computeAnchoredHeaderScrollPx(
                preMeasureScrollPx = 100,
                headerDeltaPx = -250,
                maxScrollPx = 700,
            ),
        )
        assertEquals(
            180,
            computeAnchoredHeaderScrollPx(
                preMeasureScrollPx = 0,
                headerDeltaPx = 220,
                maxScrollPx = 180,
            ),
        )
    }

    @Test
    fun `same row bring into view is suppressed only when control is already visible`() {
        assertTrue(
            shouldSuppressSameRowBringIntoView(
                suppressCenteringForSameRow = true,
                offset = 820f,
                size = 60f,
                containerSize = 1080f,
            ),
        )
        assertTrue(
            shouldSuppressSameRowBringIntoView(
                suppressCenteringForSameRow = true,
                offset = 0f,
                size = 60f,
                containerSize = 1080f,
            ),
        )
        assertTrue(
            !shouldSuppressSameRowBringIntoView(
                suppressCenteringForSameRow = false,
                offset = 820f,
                size = 60f,
                containerSize = 1080f,
            ),
        )
        assertTrue(
            !shouldSuppressSameRowBringIntoView(
                suppressCenteringForSameRow = true,
                offset = -10f,
                size = 60f,
                containerSize = 1080f,
            ),
        )
        assertTrue(
            !shouldSuppressSameRowBringIntoView(
                suppressCenteringForSameRow = true,
                offset = 1040f,
                size = 60f,
                containerSize = 1080f,
            ),
        )
    }
}
