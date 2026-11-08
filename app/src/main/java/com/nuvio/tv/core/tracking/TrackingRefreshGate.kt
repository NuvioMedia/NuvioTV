package com.nuvio.tv.core.tracking

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class TrackingRefreshGate {
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
            if (completionSequence != observedSequence && lastCompletedProfileGeneration == profileGeneration) return
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
