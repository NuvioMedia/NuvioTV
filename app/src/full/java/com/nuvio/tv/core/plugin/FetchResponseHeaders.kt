package com.nuvio.tv.core.plugin

import okhttp3.Headers

private const val MAX_FETCH_HEADER_VALUE_CHARS = 8 * 1024
private const val FETCH_TRUNCATION_SUFFIX = "\n...[truncated]"

internal fun Headers.toPluginResponseHeaders(): Map<String, String> =
    toMultimap().mapKeys { (name, _) -> name.lowercase() }.mapValues { (_, values) ->
        truncateString(values.joinToString(","), MAX_FETCH_HEADER_VALUE_CHARS)
    }

internal fun truncateString(value: String, maxChars: Int): String {
    if (value.length <= maxChars) return value
    val end = maxChars - FETCH_TRUNCATION_SUFFIX.length
    if (end <= 0) return FETCH_TRUNCATION_SUFFIX.take(maxChars)
    return value.substring(0, end) + FETCH_TRUNCATION_SUFFIX
}
