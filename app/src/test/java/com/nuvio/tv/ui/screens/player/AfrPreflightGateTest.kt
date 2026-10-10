package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AfrPreflightGateTest {

    @Test
    fun `a token stays current until the gate is cancelled`() {
        val gate = AfrPreflightGate()
        val token = gate.token()
        assertTrue(gate.isCurrent(token))
        assertTrue(gate.isCurrent(gate.token()))
    }

    @Test
    fun `a switch makes the old preflight stale and the next one current`() {
        val gate = AfrPreflightGate()
        val oldStream = gate.token()
        gate.cancel()
        val newStream = gate.token()
        assertFalse(gate.isCurrent(oldStream))
        assertTrue(gate.isCurrent(newStream))
    }

    @Test
    fun `a token taken before two switches stays stale`() {
        val gate = AfrPreflightGate()
        val first = gate.token()
        gate.cancel()
        val second = gate.token()
        gate.cancel()
        assertFalse(gate.isCurrent(first))
        assertFalse(gate.isCurrent(second))
    }
}
