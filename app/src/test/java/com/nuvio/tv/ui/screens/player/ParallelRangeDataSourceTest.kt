package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ParallelRangeDataSourceTest {

    @Test
    fun `parseRetryAfterHeaderMs reads delta-seconds`() {
        assertEquals(30_000L, ParallelRangeRetryAfter.parseHeaderMs("30"))
        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs("0"))
        assertEquals(1_000L, ParallelRangeRetryAfter.parseHeaderMs("1"))
    }

    @Test
    fun `parseRetryAfterHeaderMs reads RFC1123 HTTP-date`() {
        val now = 1_700_000_000_000L // fixed epoch for determinism
        val target = Instant.ofEpochMilli(now + 45_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(target)

        assertEquals(45_000L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }

    @Test
    fun `parseRetryAfterHeaderMs returns null for missing or garbage`() {
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(null))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(""))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs("not-a-date"))
    }

    @Test
    fun `parseRetryAfterHeaderMs clamps past HTTP-date to zero`() {
        val now = 1_700_000_000_000L
        val past = Instant.ofEpochMilli(now - 60_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(past)

        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }
}
