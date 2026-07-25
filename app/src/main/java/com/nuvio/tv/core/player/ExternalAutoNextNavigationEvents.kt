package com.nuvio.tv.core.player

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull

internal class ExternalAutoNextNavigationEvents(
    private val maxAgeMs: Long,
    private val nowMs: () -> Long = System::currentTimeMillis
) {
    private val pending = MutableStateFlow<ExternalAutoNextEpisode?>(null)

    val events: Flow<ExternalAutoNextEpisode> = pending.filterNotNull()

    @Synchronized
    fun publish(event: ExternalAutoNextEpisode) {
        pending.value = event
    }

    @Synchronized
    fun claim(event: ExternalAutoNextEpisode): Boolean {
        if (pending.value !== event) return false
        pending.value = null
        val ageMs = nowMs() - event.requestedAtMs
        return ageMs in 0..maxAgeMs
    }
}
