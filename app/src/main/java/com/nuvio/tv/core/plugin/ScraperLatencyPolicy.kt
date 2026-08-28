package com.nuvio.tv.core.plugin

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToLong

/**
 * Ranking and TTL rules for per-provider scraper latency.
 * Keys are scraper/plugin ids already used elsewhere in the app ([com.nuvio.tv.domain.model.ScraperInfo.id]).
 */
object ScraperLatencyPolicy {
    const val TTL_MS = 7L * 24L * 60L * 60L * 1000L
    const val EMA_ALPHA = 0.3
    const val TIMEOUT_MS = 120_000L
    const val FAILURE_PENALTY_MS = TIMEOUT_MS
    const val UNKNOWN_DEFAULT_MS = 10_000L

    fun recordedDurationMs(durationMs: Long, success: Boolean): Long {
        val safe = durationMs.coerceAtLeast(0L)
        return if (success) safe else maxOf(safe, FAILURE_PENALTY_MS)
    }

    fun nextEmaMs(previousEmaMs: Long?, previousSamples: Int, recordedMs: Long): Long {
        if (previousSamples <= 0 || previousEmaMs == null) return recordedMs
        return (EMA_ALPHA * recordedMs + (1.0 - EMA_ALPHA) * previousEmaMs).roundToLong()
    }

    fun isFresh(updatedAtMs: Long, nowMs: Long, ttlMs: Long = TTL_MS): Boolean {
        return updatedAtMs > 0L && nowMs - updatedAtMs in 0L..ttlMs
    }

    fun unknownSentinelMs(knownLatenciesMs: Collection<Long>): Long {
        if (knownLatenciesMs.isEmpty()) return UNKNOWN_DEFAULT_MS
        val sorted = knownLatenciesMs.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2
        } else {
            sorted[mid]
        }
    }

    fun <T> rankByCachedLatency(
        items: List<T>,
        idOf: (T) -> String,
        snapshot: Map<String, Long>
    ): List<T> {
        val sentinel = unknownSentinelMs(snapshot.values)
        return items.sortedWith(compareBy { snapshot[idOf(it)] ?: sentinel })
    }

    /**
     * Run [execute] for every item concurrently and emit as each finishes.
     * Does not join all work before the first emission.
     */
    fun <T, R> streamAsEachCompletes(
        items: List<T>,
        execute: suspend (T) -> R
    ): Flow<Pair<T, R>> = channelFlow {
        items.forEach { item ->
            launch {
                send(item to execute(item))
            }
        }
    }
}