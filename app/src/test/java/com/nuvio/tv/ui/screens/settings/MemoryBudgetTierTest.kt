package com.nuvio.tv.ui.screens.settings

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MemoryBudgetTierTest {

    @Test
    fun `low-RAM device budgets from the standard heap, not largeHeap`() {
        // 1 GB TV: dalvik.vm.heapsize 512m (largeHeap), heapgrowthlimit 192m, isLowRamDevice true
        assertEquals(192L, MemoryBudget.budgetHeapMb(maxHeapMb = 512L, standardHeapMb = 192L, deviceIsLowRam = true))
    }

    @Test
    fun `other devices keep the large heap`() {
        assertEquals(512L, MemoryBudget.budgetHeapMb(maxHeapMb = 512L, standardHeapMb = 256L, deviceIsLowRam = false))
    }

    @Test
    fun `unknown standard heap falls back to max heap`() {
        assertEquals(512L, MemoryBudget.budgetHeapMb(maxHeapMb = 512L, standardHeapMb = 0L, deviceIsLowRam = true))
    }

    @Test
    fun `a 512 MB heap on a low-RAM device is the low-RAM tier`() {
        assertTrue(MemoryBudget.lowRamTierFor(heapMb = 512L, deviceIsLowRam = true))
    }

    @Test
    fun `heap below the threshold stays low-RAM tier, large heap on a normal device does not`() {
        assertTrue(MemoryBudget.lowRamTierFor(heapMb = 256L, deviceIsLowRam = false))
        assertFalse(MemoryBudget.lowRamTierFor(heapMb = 512L, deviceIsLowRam = false))
    }
}
