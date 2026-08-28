package com.nuvio.tv.core.plugin

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.delay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperLatencyPolicyTest {

    @Test
    fun `faster completions rank ahead of slower ones`() {
        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("slow", "medium", "fast"),
            idOf = { it },
            snapshot = mapOf(
                "fast" to 120L,
                "medium" to 800L,
                "slow" to 4_000L
            )
        )
        assertEquals(listOf("fast", "medium", "slow"), ranked)
    }

    @Test
    fun `failed instant-error providers do not outrank real successes`() {
        val failedMs = ScraperLatencyPolicy.recordedDurationMs(durationMs = 4L, success = false)
        val successMs = ScraperLatencyPolicy.recordedDurationMs(durationMs = 850L, success = true)
        assertEquals(ScraperLatencyPolicy.FAILURE_PENALTY_MS, failedMs)
        assertEquals(850L, successMs)

        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("instant-404", "real-success"),
            idOf = { it },
            snapshot = mapOf(
                "instant-404" to failedMs,
                "real-success" to successMs
            )
        )
        assertEquals(listOf("real-success", "instant-404"), ranked)
    }

    @Test
    fun `failure penalty matches scraper timeout so slow successes still rank first`() {
        assertEquals(120_000L, ScraperLatencyPolicy.FAILURE_PENALTY_MS)
        assertEquals(ScraperLatencyPolicy.TIMEOUT_MS, ScraperLatencyPolicy.FAILURE_PENALTY_MS)
        val failedMs = ScraperLatencyPolicy.recordedDurationMs(durationMs = 4L, success = false)
        val slowSuccessMs = ScraperLatencyPolicy.recordedDurationMs(durationMs = 90_000L, success = true)
        assertTrue(slowSuccessMs < failedMs)
    }


    @Test
    fun `missing unknown provider uses sentinel and does not wait behind slow ones`() {
        val snapshot = mapOf("fast" to 100L, "slow" to 9_000L)
        val sentinel = ScraperLatencyPolicy.unknownSentinelMs(snapshot.values)
        assertEquals(4_550L, sentinel)

        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("slow", "unknown", "fast"),
            idOf = { it },
            snapshot = snapshot
        )
        assertEquals(listOf("fast", "unknown", "slow"), ranked)
    }

    @Test
    fun `unknown default is used when cache is empty`() {
        assertEquals(
            ScraperLatencyPolicy.UNKNOWN_DEFAULT_MS,
            ScraperLatencyPolicy.unknownSentinelMs(emptyList())
        )
        val ranked = ScraperLatencyPolicy.rankByCachedLatency(
            items = listOf("b", "a"),
            idOf = { it },
            snapshot = emptyMap()
        )
        assertEquals(listOf("b", "a"), ranked)
    }

    @Test
    fun `seven day ttl marks stale versus fresh`() {
        val now = 1_700_000_000_000L
        val ttl = ScraperLatencyPolicy.TTL_MS
        assertEquals(7L * 24L * 60L * 60L * 1000L, ttl)
        assertTrue(ScraperLatencyPolicy.isFresh(updatedAtMs = now, nowMs = now, ttlMs = ttl))
        assertTrue(ScraperLatencyPolicy.isFresh(updatedAtMs = now - ttl, nowMs = now, ttlMs = ttl))
        assertFalse(ScraperLatencyPolicy.isFresh(updatedAtMs = now - ttl - 1L, nowMs = now, ttlMs = ttl))
        assertFalse(ScraperLatencyPolicy.isFresh(updatedAtMs = 0L, nowMs = now, ttlMs = ttl))
    }

    @Test
    fun `fast providers emit without waiting for slow ones`() = runTest {
        val slowHold = CompletableDeferred<Unit>()
        val seen = mutableListOf<String>()
        val job = launch {
            ScraperLatencyPolicy.streamAsEachCompletes(listOf("slow", "fast")) { id ->
                if (id == "slow") slowHold.await() else id
                id
            }.collect { seen += it.first }
        }

        testScheduler.runCurrent()
        assertEquals(listOf("fast"), seen)
        assertTrue(job.isActive)

        slowHold.complete(Unit)
        job.join()
        assertEquals(listOf("fast", "slow"), seen)
    }

    @Test
    fun `stream emits fast completion before slow without join-all`() = runTest {
        val order = ScraperLatencyPolicy.streamAsEachCompletes(
            items = listOf("slow", "fast")
        ) { id ->
            if (id == "slow") delay(1_000) else delay(10)
            id
        }.toList().map { it.first }

        assertEquals(listOf("fast", "slow"), order)
    }
}