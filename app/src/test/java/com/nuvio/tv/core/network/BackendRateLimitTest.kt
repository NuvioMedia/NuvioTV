package com.nuvio.tv.core.network

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRateLimitTest {
    @Test
    fun retryAfterSupportsSecondsAndHttpDates() {
        assertEquals(30_000L, retryAfterDelayMillis("30", nowEpochMs = 0L))
        assertEquals(
            10_000L,
            retryAfterDelayMillis(
                retryAfterHeader = "Wed, 21 Oct 2015 07:28:00 GMT",
                nowEpochMs = 1_445_412_470_000L
            )
        )
        assertEquals(1_000L, retryAfterDelayMillis("invalid", nowEpochMs = 0L))
    }

    @Test
    fun retryDelayUsesBoundedFallbackAndAddsJitter() {
        assertEquals(1_250L, backendRetryDelayMillis(0, null, nowEpochMs = 0L, jitterMs = 250L))
        assertEquals(30_500L, backendRetryDelayMillis(10, null, nowEpochMs = 0L, jitterMs = 500L))
        assertEquals(60_250L, backendRetryDelayMillis(0, "60", nowEpochMs = 0L, jitterMs = 250L))
    }

    @Test
    fun automaticRetriesAreLimitedToReads() {
        assertTrue(isSafeBackendRetryRequest("GET", "/rest/v1/plugins"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_pull_library_delta"))
        assertTrue(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/get_my_member_access"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/sync_push_watch_progress"))
        assertFalse(isSafeBackendRetryRequest("POST", "/rest/v1/rpc/verify_profile_pin"))
        assertFalse(isSafeBackendRetryRequest("DELETE", "/rest/v1/library"))
    }

    @Test
    fun cooldownUsesRetryAfterForServiceUnavailableResponses() {
        assertTrue(shouldApplyBackendCooldown(429, null))
        assertTrue(shouldApplyBackendCooldown(503, "10"))
        assertFalse(shouldApplyBackendCooldown(503, null))
        assertFalse(shouldApplyBackendCooldown(500, "10"))
    }

    @Test
    fun coordinatorDelaysRequestsUntilLongestCooldownExpires() = runBlocking {
        var now = 1_000L
        val waits = mutableListOf<Long>()
        val coordinator = BackendRateLimitCoordinator(
            currentTimeMillis = { now },
            pause = { waitMs ->
                waits += waitMs
                now += waitMs
            }
        )

        coordinator.record("2")
        coordinator.record("5")
        coordinator.record("1")
        coordinator.awaitPermission()

        assertEquals(listOf(5_000L), waits)
    }
}
