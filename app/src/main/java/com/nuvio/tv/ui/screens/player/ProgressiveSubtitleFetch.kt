package com.nuvio.tv.ui.screens.player

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeoutOrNull

/** Publishes ID matches while the optional file hash is being probed. */
internal suspend fun <T, H> fetchProgressiveSubtitles(
    knownHash: H?,
    probeHash: suspend () -> H?,
    fetch: suspend (H?, (List<T>) -> Unit) -> List<T>,
    key: (T) -> String,
    emit: (List<T>) -> Unit
): List<T> = coroutineScope {
    val results = linkedMapOf<String, T>()
    // fetch must deliver callbacks on the caller's dispatcher, as SubtitleRepository does.
    fun publish(items: List<T>) {
        items.forEach { results[key(it)] = it }
        emit(results.values.toList())
    }
    if (knownHash != null) {
        publish(fetch(knownHash, ::publish))
    } else {
        val hash = async { probeHash() }
        // Avoid duplicate provider calls when the local probe finishes promptly.
        val quickHash = withTimeoutOrNull(100L) { hash.await() }
        if (quickHash != null) {
            publish(fetch(quickHash, ::publish))
        } else {
            val initial = async { fetch(null, ::publish).also(::publish) }
            val resolved = hash.await()
            if (resolved != null) publish(fetch(resolved, ::publish))
            initial.await()
        }
    }
    results.values.toList()
}
