package com.nuvio.tv.core.util

/**
 * Walks this throwable's cause chain (starting with itself) and returns the first
 * instance of [T], or null if none is found.
 */
inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}
