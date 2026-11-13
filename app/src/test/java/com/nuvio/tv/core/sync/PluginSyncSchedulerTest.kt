package com.nuvio.tv.core.sync

import com.nuvio.tv.data.local.PluginSyncProfile
import com.nuvio.tv.data.local.effectivePluginProfileId
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginSyncSchedulerTest {

    @Test
    fun `a second request for the same profile pulls once more after the current pull`() = runTest {
        val pulls = mutableListOf<Int>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var inFlight = 0
        var maxInFlight = 0
        val scheduler = PluginSyncScheduler(this) { profileId, _ ->
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            pulls += profileId
            if (pulls.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
            inFlight -= 1
        }

        scheduler.enqueue(1)
        firstStarted.await()
        scheduler.enqueue(1)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1, 1), pulls)
        assertEquals(1, maxInFlight)
        assertNull(PluginSyncProfile.current())
    }

    @Test
    fun `a different profile cancels the running pull and starts the requested one`() = runTest {
        val pulls = mutableListOf<Int>()
        val pins = mutableListOf<Int?>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        var replacements = 0
        val scheduler = PluginSyncScheduler(
            scope = this,
            onReplace = { replacements += 1 }
        ) { profileId, _ ->
            pulls += profileId
            pins += PluginSyncProfile.current()
            if (profileId == 1) {
                firstStarted.complete(Unit)
                suspendCancellableCoroutine<Unit> { }
            } else {
                secondStarted.complete(Unit)
            }
        }

        scheduler.enqueue(1)
        firstStarted.await()
        scheduler.enqueue(2)
        secondStarted.await()

        assertEquals(listOf(1, 2), pulls)
        assertEquals(listOf(1, 2), pins)
        assertEquals(1, replacements)
        assertNull(PluginSyncProfile.current())
    }

    @Test
    fun `profiles that share primary plugins stay on one pull`() = runTest {
        val primary = effectivePluginProfileId(usesPrimaryPlugins = false, activeProfileId = 1)
        val shared = effectivePluginProfileId(usesPrimaryPlugins = true, activeProfileId = 3)
        val independent = effectivePluginProfileId(usesPrimaryPlugins = false, activeProfileId = 2)
        assertEquals(1, primary)
        assertEquals(1, shared)
        assertEquals(2, independent)

        val pulls = mutableListOf<Int>()
        val firstStarted = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var replacements = 0
        val scheduler = PluginSyncScheduler(
            scope = this,
            onReplace = { replacements += 1 }
        ) { profileId, _ ->
            pulls += profileId
            if (pulls.size == 1) {
                firstStarted.complete(Unit)
                releaseFirst.await()
            }
        }

        scheduler.enqueue(shared)
        firstStarted.await()
        scheduler.enqueue(primary)
        releaseFirst.complete(Unit)
        advanceUntilIdle()

        assertEquals(listOf(1, 1), pulls)
        assertEquals(0, replacements)
        assertTrue(pulls.none { it == independent })
    }

    @Test
    fun `cancel drops a queued rerun`() = runTest {
        val pulls = mutableListOf<Int>()
        val started = CompletableDeferred<Unit>()
        val scheduler = PluginSyncScheduler(this) { profileId, _ ->
            pulls += profileId
            started.complete(Unit)
            suspendCancellableCoroutine<Unit> { }
        }

        scheduler.enqueue(1)
        started.await()
        scheduler.enqueue(1)
        scheduler.cancel()

        assertEquals(listOf(1), pulls)
    }

    @Test
    fun `a pull keeps the generation captured when it was queued`() = runTest {
        var generation = 0
        val seen = mutableListOf<Pair<Int, Int>>()
        val started = CompletableDeferred<Unit>()
        val scheduler = PluginSyncScheduler(
            scope = this,
            onReplace = { generation += 1 },
            currentGeneration = { generation }
        ) { profileId, token ->
            seen += profileId to token
            if (profileId == 1) {
                started.complete(Unit)
                suspendCancellableCoroutine<Unit> { }
            }
        }

        scheduler.enqueue(1)
        generation = 5
        started.await()
        scheduler.enqueue(2)
        advanceUntilIdle()

        assertEquals(listOf(1 to 0, 2 to 6), seen)
    }
}
