package com.nuvio.tv.ui.screens.detail

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AwaitOrLaterTest {
    // Like viewModelScope: a supervisor, so a failed async does not fail its scope, on the test clock.
    private fun TestScope.viewModelLikeScope() = CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

    @Test fun `work done within the wait is returned and nothing comes later`() = runTest {
        val late = mutableListOf<String>()
        val result = awaitOrLater(viewModelLikeScope(), waitMs = 3_000, fallback = "addon", work = { delay(500); "tmdb" }, onLate = { late += it })
        assertEquals("tmdb", result)
        assertEquals(500, currentTime)
        advanceUntilIdle()
        assertTrue(late.isEmpty())
    }

    @Test fun `slow work returns the fallback at the wait and delivers its result later`() = runTest {
        val late = mutableListOf<String>()
        val result = awaitOrLater(viewModelLikeScope(), waitMs = 3_000, fallback = "addon", work = { delay(20_000); "tmdb" }, onLate = { late += it })
        assertEquals("addon", result)
        assertEquals(3_000, currentTime)
        assertTrue(late.isEmpty())
        advanceUntilIdle()
        assertEquals(listOf("tmdb"), late)
        assertEquals(20_000, currentTime)
    }

    @Test fun `a failure within the wait is thrown as before`() = runTest {
        try {
            awaitOrLater(viewModelLikeScope(), waitMs = 3_000, fallback = "addon", work = { delay(100); throw IOException("down") }, onLate = {})
            fail("Expected the failure to be thrown")
        } catch (e: IOException) {
            assertEquals("down", e.message)
        }
    }

    @Test fun `a failure after the wait is reported, not delivered`() = runTest {
        val late = mutableListOf<String>()
        val errors = mutableListOf<Exception>()
        val result = awaitOrLater(viewModelLikeScope(), waitMs = 3_000, fallback = "addon",
            work = { delay(10_000); throw IOException("down") }, onLate = { late += it }, onLateError = { errors += it })
        assertEquals("addon", result)
        advanceUntilIdle()
        assertTrue(late.isEmpty())
        assertEquals(listOf("down"), errors.map { it.message })
    }

    @Test fun `a caller that goes away during the wait cancels the work`() = runTest {
        var workCancelled = false
        val late = mutableListOf<String>()
        val caller = launch {
            awaitOrLater(viewModelLikeScope(), waitMs = 3_000, fallback = "addon", work = {
                try { delay(20_000); "tmdb" } catch (e: CancellationException) { workCancelled = true; throw e }
            }, onLate = { late += it })
        }
        advanceTimeBy(1_000)
        caller.cancel()
        advanceUntilIdle()
        assertTrue(workCancelled)
        assertTrue(late.isEmpty())
    }
}
