package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeTrakrScrobbleServiceTest {
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

    private val movie = TrackingScrobbleEvent(
        TrackingMediaReference(TrackingMediaKind.MOVIE, title = "The Dark Knight", ids = TrackingExternalIds(imdb = "tt0468569")),
        96.0
    )

    private fun setup(
        harness: WeTrakrTestHarness,
        dispatcher: TestDispatcher,
        watchedPercent: Int = WETRAKR_DEFAULT_WATCHED_PERCENT
    ): Pair<WeTrakrSyncRepository, WeTrakrScrobbleService> {
        harness.connected()
        harness.store.saveUser(WeTrakrUser(42, "you"), harness.store.scope())
        val sync = WeTrakrSyncRepository(
            MemoryStorage(), harness.store, harness.api, MutableStateFlow(1), TestScope(dispatcher),
            now = { harness.now }, dispatcher = dispatcher, watchedPercentSetting = MutableStateFlow(watchedPercent)
        )
        return sync to WeTrakrScrobbleService(harness.api, sync, harness.configuration)
    }

    @Test
    fun `stop that scrobbles marks the movie watched locally`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val harness = WeTrakrTestHarness()
        val (sync, service) = setup(harness, dispatcher)
        harness.reply(status = 201, body = """{"action":"scrobble","progress":96}""")

        service.scrobble(sync.currentScope(), TrackingScrobbleAction.STOP, movie)

        assertEquals("/scrobble/stop", harness.engine.requests.single().path)
        assertTrue(sync.currentProjection().isWatched("tt0468569", null, null))
        assertTrue(sync.currentSnapshot()!!.playback.isEmpty())
    }

    @Test
    fun `stop below the threshold keeps the title paused`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val harness = WeTrakrTestHarness()
        val (sync, service) = setup(harness, dispatcher)
        harness.reply(body = """{"action":"pause","progress":42}""")

        service.scrobble(sync.currentScope(), TrackingScrobbleAction.STOP, movie.copy(progressPercent = 42.0))

        assertEquals(42f, sync.currentSnapshot()!!.playback.single().progress)
        assertEquals(false, sync.currentProjection().isWatched("tt0468569", null, null))
    }

    @Test
    fun `start and stop send the watched setting as min_progress, pause does not`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val harness = WeTrakrTestHarness()
        val (sync, service) = setup(harness, dispatcher, watchedPercent = 70)
        harness.reply(status = 201, body = """{"action":"start","progress":10}""")
        harness.reply(body = """{"action":"pause","progress":40}""")
        harness.reply(status = 201, body = """{"action":"scrobble","progress":72}""")

        service.scrobble(sync.currentScope(), TrackingScrobbleAction.START, movie.copy(progressPercent = 10.0))
        service.scrobble(sync.currentScope(), TrackingScrobbleAction.PAUSE, movie.copy(progressPercent = 40.0))
        service.scrobble(sync.currentScope(), TrackingScrobbleAction.STOP, movie.copy(progressPercent = 72.0))

        val requests = harness.engine.requests
        assertEquals(listOf("/scrobble/start", "/scrobble/pause", "/scrobble/stop"), requests.map { it.path })
        assertEquals(mapOf("min_progress" to "70"), requests[0].query)
        assertEquals(emptyMap<String, String>(), requests[1].query)
        assertEquals(mapOf("min_progress" to "70"), requests[2].query)
        assertTrue(sync.currentProjection().isWatched("tt0468569", null, null))
    }

    @Test
    fun `unknown titles change nothing`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val harness = WeTrakrTestHarness()
        val (sync, service) = setup(harness, dispatcher)
        harness.reply(status = 404, body = """{"message":"not found"}""")

        service.scrobble(sync.currentScope(), TrackingScrobbleAction.START, movie.copy(progressPercent = 10.0))

        assertTrue(sync.currentSnapshot()!!.playback.isEmpty())
        assertTrue(sync.currentSnapshot()!!.invalidatedBuckets.isEmpty())
    }
}
