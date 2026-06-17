@file:OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBudgetTest {

    @Test
    fun testTotalUsageMb() {
        // totalUsageMb(bufferMb, connectionCount, chunkSizeMb, parallelEnabled)
        // case 1: parallel disabled
        assertEquals(50, MemoryBudget.totalUsageMb(50, 4, 32, false))
        
        // case 2: parallel enabled
        // bufferCount(4) = 4 + 1 = 5
        // overhead = 5 * 32 = 160
        // total = 50 + 160 = 210
        assertEquals(210, MemoryBudget.totalUsageMb(50, 4, 32, true))
    }

    @Test
    fun testGetUsageStatusNativeAutoMode() {
        // When isNativeAutoMode is true
        // 1. usageRatio <= 1.0f should be SAFE (e.g. 400MB out of 400MB)
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, true)) // 0.5 ratio
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, true)) // 1.0 ratio
        
        // 2. usageRatio > 1.0f && <= 1.25f should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, true)) // 1.05 ratio
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, true)) // 1.2 ratio
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, true)) // 1.25 ratio
        
        // 3. usageRatio > 1.25f should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, true)) // 1.26 ratio
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, true)) // 1.5 ratio
    }

    @Test
    fun testGetUsageStatusManualMode() {
        // When isNativeAutoMode is false
        // 1. usageRatio <= 1.0f should be SAFE
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(500, 1000, false)) // 0.5 ratio
        assertEquals(MemoryUsageStatus.SAFE, MemoryBudget.getUsageStatus(1000, 1000, false)) // 1.0 ratio
        
        // 2. usageRatio > 1.0f && <= 1.25f should be WARNING
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1050, 1000, false)) // 1.05 ratio
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1200, 1000, false)) // 1.2 ratio
        assertEquals(MemoryUsageStatus.WARNING, MemoryBudget.getUsageStatus(1250, 1000, false)) // 1.25 ratio
        
        // 3. usageRatio > 1.25f should be DANGER
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1260, 1000, false)) // 1.26 ratio
        assertEquals(MemoryUsageStatus.DANGER, MemoryBudget.getUsageStatus(1500, 1000, false)) // 1.5 ratio
    }

    @Test
    fun testMemoryBudgetEnforce() {
        // If we choose values well within budget, enforce should return them unchanged
        val withinBuffer = MemoryBudget.MIN_BUFFER_MB
        val withinChunk = MemoryBudget.MIN_CHUNK_MB
        val connections = 2
        val (adjBuf, adjChunk) = MemoryBudget.enforce(withinBuffer, withinChunk, connections)
        assertEquals(withinBuffer, adjBuf)
        assertEquals(withinChunk, adjChunk)
    }
}
