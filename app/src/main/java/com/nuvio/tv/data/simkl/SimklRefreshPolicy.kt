package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingRefreshIntent
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES = 15
const val SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS =
    SIMKL_AUTOMATIC_REFRESH_INTERVAL_MINUTES * 60L * 1_000L

fun shouldRunSimklRefresh(
    intent: TrackingRefreshIntent,
    lastCheckedAtEpochMs: Long?,
    nowEpochMs: Long,
    hasError: Boolean,
    automaticIntervalMs: Long = SIMKL_AUTOMATIC_REFRESH_INTERVAL_MS
): Boolean {
    if (intent != TrackingRefreshIntent.AUTOMATIC) return true
    if (hasError || lastCheckedAtEpochMs == null) return true
    val elapsedMs = nowEpochMs - lastCheckedAtEpochMs
    return elapsedMs < 0L || elapsedMs >= automaticIntervalMs
}

class SimklRefreshGate {
    private val mutex = Mutex()
    @Volatile private var completionSequence = 0L
    private var lastCompletedProfileGeneration: Long? = null

    suspend fun runIfNeeded(
        profileGeneration: Long,
        shouldRun: () -> Boolean,
        block: suspend () -> Unit
    ) {
        val observedSequence = completionSequence
        mutex.withLock {
            if (
                completionSequence != observedSequence &&
                lastCompletedProfileGeneration == profileGeneration
            ) {
                return
            }
            if (!shouldRun()) return
            try {
                block()
            } finally {
                lastCompletedProfileGeneration = profileGeneration
                completionSequence += 1L
            }
        }
    }
}
