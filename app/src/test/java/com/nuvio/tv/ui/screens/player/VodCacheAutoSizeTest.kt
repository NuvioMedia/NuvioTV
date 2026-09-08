package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class VodCacheAutoSizeTest {

    private val mb = 1024L * 1024L
    private val gb = 1024L * mb

    private fun autoBytes(freeGb: Long): Long {
        val free = freeGb * gb
        val runtimeMax = free - gb
        return PlayerMediaSourceFactory.resolveAutoVodCacheBytes(
            freeSpaceBytes = free,
            minBytes = 100L * mb,
            runtimeMaxBytes = runtimeMax
        )
    }

    @Test
    fun `low storage takes the floor rather than a fifth of free space`() {
        assertEquals(2L * gb, autoBytes(6))
    }

    @Test
    fun `the free space ceiling still wins over the floor`() {
        assertEquals(1L * gb, autoBytes(2))
    }

    @Test
    fun `ample storage keeps the existing one fifth sizing`() {
        assertEquals(4L * gb, autoBytes(20))
    }
}
