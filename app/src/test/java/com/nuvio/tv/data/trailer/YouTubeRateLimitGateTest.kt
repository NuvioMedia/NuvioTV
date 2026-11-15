package com.nuvio.tv.data.trailer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class YouTubeRateLimitGateTest {

    private var now = 1_000_000L
    private val gate = YouTubeRateLimitGate { now }

    @Test
    fun `is open until youtube refuses a request`() {
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `stays blocked for the whole pause, then opens`() {
        val cooldown = gate.onRateLimited()

        assertEquals(YouTubeRateLimitGate.BASE_COOLDOWN_MS, cooldown)
        now += cooldown - 1
        assertTrue(gate.isBlocked())
        now += 1
        assertFalse(gate.isBlocked())
    }

    @Test
    fun `each strike doubles the pause up to the cap`() {
        val pauses = List(7) { gate.onRateLimited() }

        assertEquals(
            listOf(1L, 2L, 4L, 8L, 15L, 15L, 15L).map { it * 60_000L },
            pauses
        )
    }

    @Test
    fun `a success resets the pause`() {
        repeat(3) { gate.onRateLimited() }
        gate.onSuccess()

        assertFalse(gate.isBlocked())
        assertEquals(YouTubeRateLimitGate.BASE_COOLDOWN_MS, gate.onRateLimited())
    }
}
