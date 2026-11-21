package com.nuvio.tv.ui.screens.detail

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The result of [work] when it finishes within [waitMs]. Otherwise [fallback] at once, while [work]
 * goes on in [scope] and [onLate] receives its result when it arrives. A failure within the wait is
 * thrown as before; a failure after it goes to [onLateError].
 */
internal suspend fun <T : Any> awaitOrLater(
    scope: CoroutineScope,
    waitMs: Long,
    fallback: T,
    work: suspend () -> T,
    onLate: (T) -> Unit,
    onLateError: (Exception) -> Unit = {}
): T {
    val result = scope.async { work() }
    val inTime = try {
        withTimeoutOrNull(waitMs) { result.await() }
    } catch (e: CancellationException) {
        // The caller went away during the wait: nobody needs the result.
        result.cancel()
        throw e
    }
    if (inTime != null) return inTime
    scope.launch {
        val late = try {
            result.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onLateError(e)
            return@launch
        }
        onLate(late)
    }
    return fallback
}
