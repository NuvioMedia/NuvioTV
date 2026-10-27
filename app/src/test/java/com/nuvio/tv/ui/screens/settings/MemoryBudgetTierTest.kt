package com.nuvio.tv.ui.screens.settings

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@UnstableApi
class MemoryBudgetTierTest {

    private val oneGbTvRam = 934_536L * 1024L
    private val twoGbRam = 2L * 1024L * 1024L * 1024L

    @Test
    fun `1 GB TV with a 512 MB largeHeap budgets from half the heap`() {
        // mt5867 TV: dalvik.vm.heapsize 512m, MemTotal 934536 kB
        val snapshot = MemoryBudget.snapshotFor(maxHeapMb = 512L, physicalRamBytes = oneGbTvRam)
        assertEquals(256L, snapshot.heapMb)
        assertTrue(snapshot.isLowRamTier)
        assertEquals(100, snapshot.budgetMb)
        assertEquals(95, snapshot.conversionBudgetMb)
        assertEquals(16, snapshot.tierMaxChunkMb)
    }

    @Test
    fun `1 GB device with a standard 256 MB largeHeap is unchanged`() {
        val snapshot = MemoryBudget.snapshotFor(maxHeapMb = 256L, physicalRamBytes = oneGbTvRam)
        assertEquals(256L, snapshot.heapMb)
        assertEquals(100, snapshot.budgetMb)
    }

    @Test
    fun `512 MB heap on a device with more RAM is unchanged`() {
        val snapshot = MemoryBudget.snapshotFor(maxHeapMb = 512L, physicalRamBytes = twoGbRam)
        assertEquals(512L, snapshot.heapMb)
        assertFalse(snapshot.isLowRamTier)
        assertEquals(435, snapshot.budgetMb)
        assertEquals(128, snapshot.tierMaxChunkMb)
    }

    @Test
    fun `unknown physical RAM keeps the heap`() {
        assertEquals(512L, MemoryBudget.budgetHeapMb(maxHeapMb = 512L, physicalRamBytes = 0L))
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
