package com.nuvio.tv.data.mdblist

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class MdbListTimestampParsingTest {

    @Test
    fun `zulu timestamps match java time parsing`() {
        listOf(
            "2025-02-02T17:28:22Z",
            "2025-02-02T17:28:22.5Z",
            "2025-02-02T17:28:22.123Z",
            "2025-02-02T17:28:22.123456789Z",
            "1970-01-01T00:00:00Z"
        ).forEach { value ->
            assertEquals(value, Instant.parse(value).toEpochMilli(), mdbListTimestamp(value))
        }
    }

    @Test
    fun `offset timestamps match java time parsing`() {
        listOf(
            "2025-02-02T17:28:22+02:00",
            "2025-02-02T17:28:22-05:30",
            "2025-02-02T17:28:22.250+00:00",
            "2025-02-02T00:00:00+18:00"
        ).forEach { value ->
            assertEquals(value, OffsetDateTime.parse(value).toInstant().toEpochMilli(), mdbListTimestamp(value))
        }
    }

    @Test
    fun `space separated timestamps are treated as utc`() {
        listOf(
            "2025-02-02 17:28:22",
            "2025-02-02 17:28:22.000000",
            "2025-02-02 17:28:22.987654321"
        ).forEach { value ->
            val expected = LocalDateTime.parse(value, LOCAL_FORMAT).toInstant(ZoneOffset.UTC).toEpochMilli()
            assertEquals(value, expected, mdbListTimestamp(value))
        }
    }

    @Test
    fun `fraction is truncated to milliseconds`() {
        assertEquals(
            mdbListTimestamp("2025-02-02T17:28:22.123Z"),
            mdbListTimestamp("2025-02-02T17:28:22.123999Z")
        )
        assertEquals(
            mdbListTimestamp("2025-02-02T17:28:22.500Z"),
            mdbListTimestamp("2025-02-02T17:28:22.5Z")
        )
    }

    @Test
    fun `unsupported values still fail loudly`() {
        listOf(
            "",
            "yesterday",
            "2025-02-02",
            "2025-02-02T17:28:22",
            "2025-02-02T17:28:22Z ",
            "02/02/2025 17:28:22"
        ).forEach { value ->
            assertThrows(value, MdbListDecodingException::class.java) { mdbListTimestamp(value) }
        }
    }

    @Test
    fun `imdb id validation matches tt followed by digits`() {
        assertEquals(true, "tt12345".isMdbListImdbId())
        assertEquals(false, "tt".isMdbListImdbId())
        assertEquals(false, "tt12a".isMdbListImdbId())
        assertEquals(false, "tt-123".isMdbListImdbId())
        assertEquals(false, "12345".isMdbListImdbId())
        assertEquals(false, "tt 123".isMdbListImdbId())
    }

    private companion object {
        val LOCAL_FORMAT = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter()
    }
}
