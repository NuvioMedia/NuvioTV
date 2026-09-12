package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.delay

internal data class StartupTimeoutState(
    val isCurrentAttempt: Boolean,
    val hasStarted: Boolean,
    val isPaused: Boolean,
    val hasError: Boolean,
)

/** Waits only while this attempt is starting. A manual pause grants a fresh budget on resume. */
internal suspend fun awaitStartupTimeout(
    timeoutMs: Long,
    nowMs: () -> Long,
    state: () -> StartupTimeoutState,
): Boolean {
    var waitingSince = nowMs()
    while (true) {
        val current = state()
        if (!current.isCurrentAttempt || current.hasStarted || current.hasError) return false
        val now = nowMs()
        if (current.isPaused) {
            waitingSince = now
        } else if (now - waitingSince >= timeoutMs) {
            return true
        }
        delay(250L)
    }
}
