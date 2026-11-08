package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Tracks whether the in-app player is open, from controller creation until the player exits.
 * Unlike TvRecommendationManager.isPlaybackActive it stays set while paused, buffering or seeking,
 * so work hidden behind the player can wait for it to close instead of competing with playback.
 * Sessions are counted because the next player can open before the previous one is cleared.
 */
object PlayerSessionTracker {
    private val openSessions = MutableStateFlow(0)

    val isSessionOpen: Flow<Boolean> = openSessions.map { it > 0 }.distinctUntilChanged()

    fun open(): Session {
        openSessions.update { it + 1 }
        return Session()
    }

    class Session internal constructor() {
        private val closed = AtomicBoolean(false)

        /** Safe to call more than once; only the first call closes the session. */
        fun close() {
            if (closed.compareAndSet(false, true)) {
                openSessions.update { (it - 1).coerceAtLeast(0) }
            }
        }
    }
}
