package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeTrakrSyncPauseTest {
    private class MemoryStorage : WeTrakrSyncStorage {
        val payloads = mutableMapOf<Int, String>()
        override suspend fun load(profileId: Int): String? = payloads[profileId]
        override suspend fun save(profileId: Int, payload: String, checkScope: () -> Unit) {
            checkScope()
            payloads[profileId] = payload
        }
        override suspend fun remove(profileId: Int, checkScope: () -> Unit) {
            payloads.remove(profileId)
        }
    }

    @Test
    fun `only limit errors with a future reset are a pause`() {
        val now = 1_000L

        assertEquals(
            WeTrakrSyncPause(5_000L, WeTrakrSyncError.QUOTA_EXCEEDED),
            WeTrakrSyncState(error = WeTrakrSyncError.QUOTA_EXCEEDED, retryAtEpochMs = 5_000L).pause(now)
        )
        assertEquals(
            WeTrakrSyncPause(5_000L, WeTrakrSyncError.RATE_LIMIT),
            WeTrakrSyncState(error = WeTrakrSyncError.RATE_LIMIT, retryAtEpochMs = 5_000L).pause(now)
        )
        assertNull(WeTrakrSyncState(error = WeTrakrSyncError.QUOTA_EXCEEDED, retryAtEpochMs = 500L).pause(now))
        assertNull(WeTrakrSyncState(error = WeTrakrSyncError.UNAVAILABLE, retryAtEpochMs = 5_000L).pause(now))
        assertNull(WeTrakrSyncState(error = WeTrakrSyncError.QUOTA_EXCEEDED).pause(now))
    }

    @Test
    fun `sync resumes on its own once the daily limit resets`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val harness = WeTrakrTestHarness()
        harness.connected()
        harness.store.saveUser(WeTrakrUser(42, "you"), harness.store.scope())
        val sync = WeTrakrSyncRepository(
            MemoryStorage(), harness.store, harness.api, MutableStateFlow(1), TestScope(dispatcher),
            now = { harness.now }, dispatcher = dispatcher
        )
        harness.reply(status = 429, body = """{"error":"QUOTA_EXCEEDED"}""", headers = mapOf("X-Quota-Reset" to "60"))

        sync.refresh(TrackingRefreshIntent.USER_INITIATED)

        val paused = sync.state.value.pause(harness.now)
        assertEquals(WeTrakrSyncError.QUOTA_EXCEEDED, paused?.reason)
        assertEquals(harness.now + 60_000L, paused?.untilEpochMs)

        harness.reply(body = "{}")
        repeat(6) { harness.reply(body = "[]") }
        harness.now += 60_000L
        advanceTimeBy(60_001L)
        runCurrent()

        assertNull(sync.state.value.error)
        assertTrue(sync.state.value.hasLoaded)
        assertEquals(8, harness.engine.requests.size)
    }
}
