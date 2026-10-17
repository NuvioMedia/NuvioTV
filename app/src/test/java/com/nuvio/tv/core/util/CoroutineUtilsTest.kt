package com.nuvio.tv.core.util

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoroutineUtilsTest {
    @Test
    fun `returns success with the block result`() = runBlocking {
        val result = runCatchingCancellable { 42 }
        assertEquals(42, result.getOrNull())
    }

    @Test
    fun `wraps ordinary exceptions in a failure`() = runBlocking {
        val error = IOException("boom")
        val result = runCatchingCancellable<Int> { throw error }
        assertTrue(result.isFailure)
        assertEquals(error, result.exceptionOrNull())
    }

    @Test
    fun `cancellation of a child while the caller is active is returned as a failure`() = runBlocking {
        val result = runCatchingCancellable<Int> { throw CancellationException("child cancelled") }
        assertTrue(result.isFailure)
    }

    @Test
    fun `rethrows when the calling coroutine is cancelled`() = runBlocking {
        var reachedAfter = false
        val job = launch(start = CoroutineStart.UNDISPATCHED) {
            runCatchingCancellable { delay(Long.MAX_VALUE) }
            reachedAfter = true
        }
        job.cancelAndJoin()
        assertFalse(reachedAfter)
    }
}
