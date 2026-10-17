package com.nuvio.tv.core.util

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Like [runCatching], but does not swallow cancellation of the calling coroutine.
 *
 * If the calling coroutine has been cancelled, the failure is rethrown instead of being wrapped
 * in a [Result]. A [kotlinx.coroutines.CancellationException] thrown while the calling coroutine
 * is still active (a timeout, or a child job that was cancelled) is still returned as a failure.
 */
suspend inline fun <T> runCatchingCancellable(block: () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (e: Throwable) {
        currentCoroutineContext().ensureActive()
        Result.failure(e)
    }
