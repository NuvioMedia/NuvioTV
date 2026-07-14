package com.nuvio.tv.ui.screens.player

import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

// HTTP Retry-After → remaining wait ms (delta-seconds or RFC 1123 date).
internal object ParallelRangeRetryAfter {
    fun parseHeaderMs(
        header: String?,
        nowEpochMs: Long = System.currentTimeMillis()
    ): Long? {
        if (header.isNullOrEmpty()) return null
        header.toLongOrNull()?.let { seconds ->
            return (seconds * 1000L).coerceAtLeast(0L)
        }
        return try {
            val target = ZonedDateTime.parse(
                header,
                DateTimeFormatter.RFC_1123_DATE_TIME.withLocale(Locale.US)
            )
            (target.toInstant().toEpochMilli() - nowEpochMs).coerceAtLeast(0L)
        } catch (_: Exception) {
            null
        }
    }
}
