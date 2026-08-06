package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklRefreshPolicyTest {
    @Test
    fun `automatic refresh is throttled for fifteen minutes`() {
        val lastCheckedAt = 1_000L

        assertEquals(15, SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES)
        assertFalse(
            shouldRunSimklRefresh(
                TrackingRefreshIntent.AUTOMATIC,
                lastCheckedAt,
                lastCheckedAt + SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS - 1L,
                false
            )
        )
        assertTrue(
            shouldRunSimklRefresh(
                TrackingRefreshIntent.AUTOMATIC,
                lastCheckedAt,
                lastCheckedAt + SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS,
                false
            )
        )
    }

    @Test
    fun `missing stale clock or failed snapshots refresh automatically`() {
        assertTrue(shouldRunSimklRefresh(TrackingRefreshIntent.AUTOMATIC, null, 1_000L, false))
        assertTrue(shouldRunSimklRefresh(TrackingRefreshIntent.AUTOMATIC, 1_000L, 1_001L, true))
        assertTrue(shouldRunSimklRefresh(TrackingRefreshIntent.AUTOMATIC, 2_000L, 1_000L, false))
    }

    @Test
    fun `manual and invalidated refreshes bypass automatic freshness`() {
        TrackingRefreshIntent.entries
            .filterNot { it == TrackingRefreshIntent.AUTOMATIC }
            .forEach { intent ->
                assertTrue(shouldRunSimklRefresh(intent, 1_000L, 1_001L, false))
            }
    }

    @Test
    fun `overlapping refreshes for one profile generation are coalesced`() = runBlocking {
        val gate = SimklRefreshGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        var executions = 0

        val first = async {
            gate.runIfNeeded(7L, { true }) {
                executions += 1
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.runIfNeeded(7L, { true }) { executions += 1 }
        }
        releaseFirst.complete(Unit)

        first.await()
        second.await()
        assertEquals(1, executions)
    }

    @Test
    fun `a new profile generation is not coalesced with an old profile refresh`() = runBlocking {
        val gate = SimklRefreshGate()
        val firstEntered = CompletableDeferred<Unit>()
        val releaseFirst = CompletableDeferred<Unit>()
        val executed = mutableListOf<Long>()

        val first = async {
            gate.runIfNeeded(1L, { true }) {
                executed += 1L
                firstEntered.complete(Unit)
                releaseFirst.await()
            }
        }
        firstEntered.await()
        val second = async {
            gate.runIfNeeded(2L, { true }) { executed += 2L }
        }
        releaseFirst.complete(Unit)

        first.await()
        second.await()
        assertEquals(listOf(1L, 2L), executed)
    }
}
