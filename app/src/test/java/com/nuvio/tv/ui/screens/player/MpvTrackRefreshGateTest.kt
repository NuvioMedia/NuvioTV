package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MpvTrackRefreshGateTest {

    @Test
    fun `a new gate is stale so the first tick reads the tracks`() {
        assertTrue(MpvTrackRefreshGate().isStale)
    }

    @Test
    fun `a refresh clears the gate until the next change`() {
        val gate = MpvTrackRefreshGate()
        gate.onRefreshStarted()
        assertFalse(gate.isStale)

        gate.markStale()
        assertTrue(gate.isStale)
    }

    @Test
    fun `a change that lands during a refresh keeps the gate stale`() {
        val gate = MpvTrackRefreshGate()
        gate.onRefreshStarted()
        // Track-list event arrives while the snapshot is being read.
        gate.markStale()
        assertTrue(gate.isStale)
    }

    @Test
    fun `several changes before a tick need a single refresh`() {
        val gate = MpvTrackRefreshGate()
        gate.markStale()
        gate.markStale()
        gate.markStale()
        gate.onRefreshStarted()
        assertFalse(gate.isStale)
    }
}
