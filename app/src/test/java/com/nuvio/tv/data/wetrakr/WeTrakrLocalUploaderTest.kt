package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.domain.model.WatchedItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class WeTrakrLocalUploaderTest {
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

    private val snapshot = WeTrakrSyncSnapshot(accountId = 42, isInitialized = true)
    private val runsAtSleep = mutableListOf<WeTrakrUploadRun?>()

    private fun preview(count: Int) = weTrakrUploadPreview(
        watched = (1..count).map { WatchedItem("tt${1_000_000 + it}", "movie", "Movie $it", null, null, 1_700_000_000_000L) },
        library = emptyList(),
        snapshot = snapshot
    )

    private fun TestScope.setup(harness: WeTrakrTestHarness): WeTrakrLocalUploader {
        val dispatcher = StandardTestDispatcher(testScheduler)
        harness.connected()
        harness.store.saveUser(WeTrakrUser(42, "you"), harness.store.scope())
        val sync = WeTrakrSyncRepository(
            MemoryStorage(), harness.store, harness.api, MutableStateFlow(1), TestScope(dispatcher),
            now = { harness.now }, dispatcher = dispatcher, remote = { WeTrakrTestRemote() }
        )
        lateinit var uploader: WeTrakrLocalUploader
        uploader = WeTrakrLocalUploader(
            harness.api, sync, harness.store, TestScope(dispatcher),
            now = { harness.now },
            sleep = {
                runsAtSleep += uploader.run.value
                harness.sleeps += it
                delay(it)
                harness.now += it
            }
        )
        return uploader
    }

    @Test
    fun `watched history is sent without rewatches and errored titles count as already on WeTrakr`() = runTest {
        val harness = WeTrakrTestHarness()
        val uploader = setup(harness)
        harness.reply(body = """{"added":{"movies":1},"errored":{"movies":[{"ids":{"imdb":"tt1000002"},"status":"watched","error":"Already watched! Send allow_rewatch: true to add another play"}]}}""")

        assertTrue(uploader.start(harness.store.scope(), preview(2), setOf(WeTrakrUploadKind.WATCHED)))
        advanceUntilIdle()

        val request = harness.engine.requests.single()
        assertEquals("/sync/tracking", request.path)
        assertNotNull(request.headers["Idempotency-Key"])
        val body = Json.parseToJsonElement(request.body!!).jsonObject
        assertEquals("false", body["allow_rewatch"]!!.jsonPrimitive.content)
        val movie = body["movies"]!!.jsonArray.first().jsonObject
        assertEquals("watched", movie["status"]!!.jsonPrimitive.content)
        assertEquals("2023-11-14T22:13:20Z", movie["tracked_at"]!!.jsonPrimitive.content)
        val run = uploader.run.value!!
        assertEquals(WeTrakrUploadStatus.DONE, run.status)
        assertEquals(2, run.sent)
        assertEquals(1, run.added)
        assertEquals(1, run.alreadyOnWeTrakr)
    }

    @Test
    fun `rate limited batch pauses and is retried with the same idempotency key`() = runTest {
        val harness = WeTrakrTestHarness()
        val uploader = setup(harness)
        harness.reply(status = 429, body = """{"error":"QUOTA_EXCEEDED"}""", headers = mapOf("X-Quota-Reset" to "120"))
        harness.reply(body = "{}")

        uploader.start(harness.store.scope(), preview(1), setOf(WeTrakrUploadKind.WATCHED))
        advanceUntilIdle()

        val (first, second) = harness.engine.requests
        assertEquals(first.headers["Idempotency-Key"], second.headers["Idempotency-Key"])
        assertEquals(first.body, second.body)
        assertTrue(harness.sleeps.any { it >= 120_000L })
        assertEquals(WeTrakrUploadStatus.DONE, uploader.run.value!!.status)
        assertEquals(1, uploader.run.value!!.added)
    }

    @Test
    fun `low quota headroom pauses before the next batch and library is sent as planning`() = runTest {
        val harness = WeTrakrTestHarness()
        val uploader = setup(harness)
        val many = weTrakrUploadPreview(
            watched = (1..WETRAKR_UPLOAD_BATCH_SIZE + 1).map { WatchedItem("tt${2_000_000 + it}", "movie", "M", null, null, 0L) },
            library = emptyList(),
            snapshot = snapshot
        )
        harness.reply(body = "{}", headers = mapOf("X-Quota-Remaining" to "50", "X-Quota-Reset" to "300"))
        harness.reply(body = "{}")
        uploader.start(harness.store.scope(), many, setOf(WeTrakrUploadKind.WATCHED))
        advanceUntilIdle()
        val paused = runsAtSleep.filterNotNull().filter { it.status == WeTrakrUploadStatus.PAUSED }

        assertEquals(2, harness.engine.requests.size)
        assertEquals(WeTrakrSyncError.QUOTA_EXCEEDED, paused.firstOrNull()?.pause?.reason)
        assertTrue(harness.sleeps.any { it >= 300_000L })
        assertEquals(WETRAKR_UPLOAD_BATCH_SIZE + 1, uploader.run.value!!.added)
        assertNotEqualsKeys(harness.engine.requests)

        val planning = weTrakrUploadBatches(
            WeTrakrUploadPreview(
                watched = WeTrakrUploadGroup(WeTrakrUploadKind.WATCHED),
                library = WeTrakrUploadGroup(
                    WeTrakrUploadKind.LIBRARY,
                    listOf(WeTrakrTrackingChange(WeTrakrTarget(WeTrakrItemType.MOVIE, weTrakrMovie("tt7")), null))
                )
            ),
            WeTrakrUploadKind.entries.toSet()
        )
        assertEquals(WeTrakrUploadKind.LIBRARY, planning.single().kind)
    }

    @Test
    fun `cancel stops the upload and nothing starts without changes`() = runTest {
        val harness = WeTrakrTestHarness()
        val uploader = setup(harness)

        assertFalse(uploader.start(harness.store.scope(), preview(0), WeTrakrUploadKind.entries.toSet()))
        assertNull(uploader.run.value)

        harness.reply(status = 429, body = """{"error":"rate_limited"}""", headers = mapOf("Retry-After" to "30"))
        uploader.start(harness.store.scope(), preview(1), setOf(WeTrakrUploadKind.WATCHED))
        testScheduler.runCurrent()
        uploader.cancel()
        advanceUntilIdle()

        assertEquals(WeTrakrUploadStatus.CANCELLED, uploader.run.value!!.status)
        assertEquals(0, uploader.run.value!!.added)
        uploader.clearFinished()
        assertNull(uploader.run.value)
    }

    private fun assertNotEqualsKeys(requests: List<WeTrakrHttpRequest>) {
        assertEquals(requests.size, requests.map { it.headers["Idempotency-Key"] }.toSet().size)
    }
}
