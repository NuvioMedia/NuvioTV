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

    @Test
    fun `1 GB low-RAM TV snapshot budgets from the 192 MB standard heap`() {
        val snapshot = MemoryBudget.snapshotFor(maxHeapMb = 512L, standardHeapMb = 192L, deviceIsLowRam = true)
        assertEquals(192L, snapshot.heapMb)
        assertTrue(snapshot.isLowRamTier)
        assertEquals(96, snapshot.budgetMb)
        assertEquals(91, snapshot.conversionBudgetMb)
        assertEquals(16, snapshot.tierMaxChunkMb)
    }

    @Test
    fun `low-RAM device already in the low tier also budgets from the standard heap`() {
        // largeHeap 384 MB, memory class 192 MB: 174 MB from the large heap before, 96 MB now.
        assertEquals(174, MemoryBudget.snapshotFor(384L, standardHeapMb = 0L, deviceIsLowRam = true).budgetMb)
        assertEquals(96, MemoryBudget.snapshotFor(384L, standardHeapMb = 192L, deviceIsLowRam = true).budgetMb)
    }

    @Test
    fun `device not marked low-RAM keeps its large-heap snapshot`() {
        val snapshot = MemoryBudget.snapshotFor(maxHeapMb = 512L, standardHeapMb = 256L, deviceIsLowRam = false)
        assertEquals(512L, snapshot.heapMb)
        assertFalse(snapshot.isLowRamTier)
        assertEquals(435, snapshot.budgetMb)
        assertEquals(128, snapshot.tierMaxChunkMb)
    }

    @Test
    fun `stored target buffer above the slider limit is capped unless large buffers are allowed`() {
        val overheadMb = 64
        val limitMb = MemoryBudget.maxBufferMb(overheadMb)
        val stored = limitMb + 500
        assertEquals(limitMb, MemoryBudget.playerTargetBufferMb(stored, overheadMb, allowLargeTargetBuffer = false))
        assertEquals(stored, MemoryBudget.playerTargetBufferMb(stored, overheadMb, allowLargeTargetBuffer = true))
        assertEquals(limitMb, MemoryBudget.playerTargetBufferMb(limitMb, overheadMb, allowLargeTargetBuffer = false))
    }
}
