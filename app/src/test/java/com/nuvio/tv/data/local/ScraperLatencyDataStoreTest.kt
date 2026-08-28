package com.nuvio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import com.nuvio.tv.core.plugin.ScraperLatencyPolicy
import com.nuvio.tv.core.profile.ProfileManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperLatencyDataStoreTest {

    @Test
    fun `per provider cache write then read`() = runTest {
        val harness = harness(nowMs = 1_000L)
        harness.store.record("provider-a", durationMs = 250L, success = true)
        harness.store.record("provider-b", durationMs = 1_200L, success = true)

        val snapshot = harness.store.snapshot()
        assertEquals(250L, snapshot["provider-a"])
        assertEquals(1_200L, snapshot["provider-b"])
        assertEquals(2, snapshot.size)
    }

    @Test
    fun `seven day ttl expiry stale versus fresh`() = runTest {
        val now = MutableStateFlow(10_000L)
        val harness = harness { now.value }

        harness.store.record("fresh", durationMs = 300L, success = true)
        now.value = 10_000L + ScraperLatencyPolicy.TTL_MS
        harness.store.record("still-fresh-edge", durationMs = 400L, success = true)

        var snapshot = harness.store.snapshot()
        assertTrue(snapshot.containsKey("fresh"))
        assertTrue(snapshot.containsKey("still-fresh-edge"))

        now.value = 10_000L + ScraperLatencyPolicy.TTL_MS + 1L
        snapshot = harness.store.snapshot()
        assertFalse(snapshot.containsKey("fresh"))
        assertTrue(snapshot.containsKey("still-fresh-edge"))
        assertEquals(400L, snapshot["still-fresh-edge"])
    }

    @Test
    fun `failed providers are penalized so they do not rank first`() = runTest {
        val harness = harness(nowMs = 50L)
        harness.store.record("npe-provider", durationMs = 2L, success = false)
        harness.store.record("ok-provider", durationMs = 900L, success = true)

        val snapshot = harness.store.snapshot()
        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("npe-provider", "ok-provider"),
            idOf = { it },
            snapshot = snapshot
        )
        assertEquals(ScraperLatencyPolicy.FAILURE_PENALTY_MS, snapshot["npe-provider"])
        assertEquals(900L, snapshot["ok-provider"])
        assertEquals(listOf("ok-provider", "npe-provider"), ranked)
    }

    @Test
    fun `empty successful scrape is not penalized`() = runTest {
        val harness = harness(nowMs = 50L)
        harness.store.record("empty-ok", durationMs = 400L, success = true)
        harness.store.record("crash", durationMs = 2L, success = false)

        val snapshot = harness.store.snapshot()
        assertEquals(400L, snapshot["empty-ok"])
        assertEquals(ScraperLatencyPolicy.FAILURE_PENALTY_MS, snapshot["crash"])
        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("crash", "empty-ok"),
            idOf = { it },
            snapshot = snapshot
        )
        assertEquals(listOf("empty-ok", "crash"), ranked)
    }

    @Test
    fun `unknown provider is absent from snapshot and ranks by default sentinel`() = runTest {
        val harness = harness(nowMs = 50L)
        harness.store.record("known-fast", durationMs = 100L, success = true)
        harness.store.record("known-slow", durationMs = 8_000L, success = true)

        val snapshot = harness.store.snapshot()
        assertFalse(snapshot.containsKey("brand-new"))

        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("known-slow", "brand-new", "known-fast"),
            idOf = { it },
            snapshot = snapshot
        )
        assertEquals(listOf("known-fast", "brand-new", "known-slow"), ranked)
    }

    private fun harness(nowMs: Long): Harness = harness { nowMs }

    private fun harness(clock: () -> Long): Harness {
        val prefs = TestPreferencesDataStore()
        val factory = mockk<ProfileDataStoreFactory>()
        every { factory.get(any(), any()) } returns prefs
        val profileManager = mockk<ProfileManager>()
        every { profileManager.activeProfileId } returns MutableStateFlow(1)
        return Harness(
            store = ScraperLatencyDataStore(factory, profileManager, clock),
            prefs = prefs
        )
    }

    private data class Harness(
        val store: ScraperLatencyDataStore,
        val prefs: TestPreferencesDataStore
    )

    private class TestPreferencesDataStore(
        initial: Preferences = emptyPreferences()
    ) : DataStore<Preferences> {
        private val mutex = Mutex()
        private val state = MutableStateFlow(initial)

        override val data: Flow<Preferences> = state

        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
            return mutex.withLock {
                transform(state.value).also { updated ->
                    state.value = updated
                }
            }
        }
    }
}