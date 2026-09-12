package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerStartupTimeoutTest {
    private val starting = StartupTimeoutState(
        isCurrentAttempt = true, hasStarted = false, isPaused = false, hasError = false,
    )

    @Test fun `hung startup times out after its budget`() = runTest {
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { starting } }
        advanceTimeBy(24_999)
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun `first frame at deadline wins over timeout`() = runTest {
        var state = starting
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { state } }
        advanceTimeBy(25_000)
        state = state.copy(hasStarted = true)
        runCurrent()
        assertFalse(result.await())
    }

    @Test fun `released or replaced attempt cannot report timeout`() = runTest {
        var state = starting
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { state } }
        advanceTimeBy(10_000)
        state = state.copy(isCurrentAttempt = false)
        runCurrent()
        assertFalse(result.await())
    }

    @Test fun `real error ends watchdog without overwriting it`() = runTest {
        var state = starting
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { state } }
        advanceTimeBy(10_000)
        state = state.copy(hasError = true)
        runCurrent()
        assertFalse(result.await())
    }

    @Test fun `long manual pause does not consume startup budget`() = runTest {
        var state = starting
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { state } }
        advanceTimeBy(20_000)
        state = state.copy(isPaused = true)
        advanceTimeBy(60_000)
        runCurrent()
        assertFalse(result.isCompleted)
        state = state.copy(isPaused = false)
        advanceTimeBy(24_999)
        assertFalse(result.isCompleted)
        advanceTimeBy(1)
        runCurrent()
        assertTrue(result.await())
    }

    @Test fun `cancellation prevents late timeout`() = runTest {
        val result = async { awaitStartupTimeout(25_000, { testScheduler.currentTime }) { starting } }
        advanceTimeBy(10_000)
        result.cancel()
        advanceTimeBy(30_000)
        assertTrue(result.isCancelled)
    }

    @Test fun `late successful startup clears only its own timeout`() {
        assertNull(errorAfterStartupRecovery("startup timeout", "startup timeout"))
        assertEquals("decoder failed", errorAfterStartupRecovery("decoder failed", "startup timeout"))
        assertEquals("network failed", errorAfterStartupRecovery("network failed", null))
        assertNull(errorAfterStartupRecovery(null, "startup timeout"))
    }
}
