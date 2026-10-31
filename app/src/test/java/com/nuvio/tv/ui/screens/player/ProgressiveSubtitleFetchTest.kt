package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class ProgressiveSubtitleFetchTest {
    @Test fun hashMatchesKeepPriorityWhenInitialResponseFinishesLater() = runTest {
        val result = fetchProgressiveSubtitles<Pair<String, String>, String>(
            knownHash = null,
            probeHash = { delay(300); "hash" },
            fetch = { hash, emit ->
                if (hash == null) {
                    val items = listOf("same" to "generic", "id-only" to "generic")
                    emit(items)
                    delay(400)
                    items
                } else {
                    listOf("same" to "exact", "hash-only" to "exact")
                }
            },
            key = { it.first }, emit = {}
        )
        assertEquals(listOf("same" to "exact", "hash-only" to "exact", "id-only" to "generic"), result)
    }

    @Test fun slowHashDoesNotHoldBackFirstProviderResult() = runTest {
        val times = mutableListOf<Long>()
        val result = fetchProgressiveSubtitles<String, String>(
            knownHash = null,
            probeHash = { delay(1500); "hash" },
            fetch = { hash, emit -> delay(50); listOf(hash ?: "id").also(emit) },
            key = { it },
            emit = { if (it.isNotEmpty()) times += testScheduler.currentTime }
        )
        assertEquals(150L, times.first())
        assertEquals(setOf("id", "hash"), result.toSet())
    }

    @Test fun knownHashDoesNotProbeOrDuplicateRequests() = runTest {
        val requests = mutableListOf<String?>()
        val result = fetchProgressiveSubtitles(
            knownHash = "cached",
            probeHash = { error("Must not probe") },
            fetch = { hash, emit -> requests += hash; listOf("sub").also(emit) },
            key = { it }, emit = {}
        )
        assertEquals(listOf("cached"), requests)
        assertEquals(listOf("sub"), result)
    }

    @Test fun quickHashOnlyQueriesProvidersOnce() = runTest {
        val requests = mutableListOf<String?>()
        fetchProgressiveSubtitles(
            knownHash = null,
            probeHash = { delay(10); "hash" },
            fetch = { hash, _ -> requests += hash; listOf("sub") },
            key = { it }, emit = {}
        )
        assertEquals(listOf("hash"), requests)
    }

    @Test fun failedProbeKeepsIdResultsAndDeduplicatesPartialEmissions() = runTest {
        val requests = mutableListOf<String?>()
        val result = fetchProgressiveSubtitles<String, String>(
            knownHash = null,
            probeHash = { delay(500); null },
            fetch = { hash, emit -> requests += hash; emit(listOf("same")); listOf("same", "other") },
            key = { it }, emit = {}
        )
        assertEquals(listOf<String?>(null), requests)
        assertEquals(listOf("same", "other"), result)
    }

    @Test fun cancellationStopsBothProbeAndProviderWork() = runTest {
        var probeCancelled = false
        var fetchCancelled = false
        val job = launch {
            fetchProgressiveSubtitles<String, String>(null,
                probeHash = { try { delay(5000); "hash" } finally { probeCancelled = true } },
                fetch = { _, _ -> try { delay(5000); listOf("sub") } finally { fetchCancelled = true } },
                key = { it }, emit = { fail("Cancelled request published") }
            )
        }
        testScheduler.advanceTimeBy(200)
        job.cancel(CancellationException("Switched stream"))
        job.join()
        assertTrue(probeCancelled)
        assertTrue(fetchCancelled)
    }
}
