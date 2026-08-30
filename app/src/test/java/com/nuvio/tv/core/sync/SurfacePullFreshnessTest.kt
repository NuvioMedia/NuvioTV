package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SurfacePullFreshnessTest {
    @Test
    fun recentMatchingPullIsFresh() {
        val freshness = SurfacePullFreshness(key = "user_p1", pulledAtMs = 1_000L)

        assertTrue(freshness.isRecent("user_p1", nowMs = 1_999L, minIntervalMs = 1_000L))
    }

    @Test
    fun differentExpiredOrFuturePullIsNotFresh() {
        val freshness = SurfacePullFreshness(key = "user_p1", pulledAtMs = 1_000L)

        assertFalse(freshness.isRecent("user_p2", nowMs = 1_500L, minIntervalMs = 1_000L))
        assertFalse(freshness.isRecent("user_p1", nowMs = 2_000L, minIntervalMs = 1_000L))
        assertFalse(freshness.isRecent("user_p1", nowMs = 999L, minIntervalMs = 1_000L))
    }
}
