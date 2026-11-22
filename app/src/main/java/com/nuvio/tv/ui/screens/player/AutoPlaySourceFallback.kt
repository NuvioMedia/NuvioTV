package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.domain.model.Stream

/** What resolving one auto-play stream gave. */
internal sealed interface AutoPlayResolveAttempt<out T, out F> {
    /** The stream is ready to play. */
    data class Resolved<T>(val value: T) : AutoPlayResolveAttempt<T, Nothing>

    /** The stream cannot be played, in a way the next source may get past. */
    data class Failed<F>(val failure: F) : AutoPlayResolveAttempt<Nothing, F>

    /** The stream cannot be played and the failure was shown already, which ends the auto-play. */
    data object Ended : AutoPlayResolveAttempt<Nothing, Nothing>
}

internal data class AutoPlayFallbackRun<T, F>(
    /** What the stream that resolved gave, or null when none did. */
    val resolved: T?,
    /** The failure of the last stream tried, to show when none resolved; null once it was shown. */
    val failure: F?,
    /** Every stream tried, the one that resolved included, by its fallback key. */
    val triedKeys: Set<String>,
    /** How many further streams were tried after the first one. */
    val attempts: Int
)

/**
 * Resolves [first] and, while a stream fails in a way the next source may get past, the stream
 * [nextCandidate] picks next, up to [maxSources] further ones.
 */
internal suspend fun <T : Any, F : Any> resolveWithSourceFallback(
    first: Stream,
    maxSources: Int,
    resolve: suspend (Stream) -> AutoPlayResolveAttempt<T, F>,
    nextCandidate: (failed: Stream, triedKeys: Set<String>) -> Stream?
): AutoPlayFallbackRun<T, F> {
    val triedKeys = mutableSetOf<String>()
    var attempts = 0
    var candidate = first
    while (true) {
        val attempt = resolve(candidate)
        triedKeys += candidate.sourceFallbackKey()
        val failure = when (attempt) {
            is AutoPlayResolveAttempt.Resolved -> {
                return AutoPlayFallbackRun(attempt.value, failure = null, triedKeys, attempts)
            }
            AutoPlayResolveAttempt.Ended -> {
                return AutoPlayFallbackRun(resolved = null, failure = null, triedKeys, attempts)
            }
            is AutoPlayResolveAttempt.Failed -> attempt.failure
        }
        val next = if (attempts < maxSources) nextCandidate(candidate, triedKeys) else null
        if (next == null) return AutoPlayFallbackRun(resolved = null, failure, triedKeys, attempts)
        attempts++
        candidate = next
    }
}
