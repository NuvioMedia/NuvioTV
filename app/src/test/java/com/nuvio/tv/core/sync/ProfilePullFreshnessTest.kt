package com.nuvio.tv.core.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfilePullFreshnessTest {
    @Test
    fun recentMatchingAccountPullIsFresh() {
        val freshness = ProfilePullFreshness(userId = "user", pulledAtMs = 1_000L)

        assertTrue(freshness.isRecent("user", nowMs = 10_999L))
    }

    @Test
    fun differentExpiredOrFuturePullIsNotFresh() {
        val freshness = ProfilePullFreshness(userId = "user", pulledAtMs = 1_000L)

        assertFalse(freshness.isRecent("other", nowMs = 2_000L))
        assertFalse(freshness.isRecent("user", nowMs = 11_000L))
        assertFalse(freshness.isRecent("user", nowMs = 999L))
    }
}
