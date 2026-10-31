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
    val initialResults = linkedMapOf<String, T>()
    val matchedResults = linkedMapOf<String, T>()
    fun results(): List<T> = (matchedResults.values + initialResults.values).distinctBy(key)
    // fetch must deliver callbacks on the caller's dispatcher, as SubtitleRepository does.
    fun publishInitial(items: List<T>) {
        items.forEach { initialResults[key(it)] = it }
        emit(results())
    }
    fun publishMatched(items: List<T>) {
        items.forEach { matchedResults[key(it)] = it }
        emit(results())
    }
    if (knownHash != null) {
        publishMatched(fetch(knownHash, ::publishMatched))
    } else {
        val hash = async { probeHash() }
        // Avoid duplicate provider calls when the local probe finishes promptly.
        val quickHash = withTimeoutOrNull(100L) { hash.await() }
        if (quickHash != null) {
            publishMatched(fetch(quickHash, ::publishMatched))
        } else {
            val initial = async { fetch(null, ::publishInitial).also(::publishInitial) }
            val resolved = hash.await()
            if (resolved != null) publishMatched(fetch(resolved, ::publishMatched))
            initial.await()
        }
    }
    results()
}
