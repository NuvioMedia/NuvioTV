package com.nuvio.tv.data.mdblist

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatterBuilder
import java.time.temporal.ChronoField
import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class MdbListTimestampDifferentialTest {

    @Test
    fun `fast path agrees with java time on structured corpus`() {
        assertMatchesOracle(structuredCorpus())
    }

    @Test
    fun `fast path agrees with java time on randomized corpus`() {
        assertMatchesOracle(randomCorpus(Random(20_261_020), RANDOM_COUNT))
    }

    @Test
    fun `fast path agrees with java time on mutated corpus`() {
        assertMatchesOracle(mutatedCorpus(Random(7_391)))
    }

    private fun assertMatchesOracle(values: List<String>) {
        val mismatches = ArrayList<String>()
        values.forEach { value ->
            val expected = oracle(value)
            val actual = runCatching { mdbListTimestamp(value) }
            if (expected == null) {
                if (actual.isSuccess) {
                    mismatches += "accepted <$value> as ${actual.getOrNull()} but java time rejects it"
                }
            } else if (actual.getOrNull() != expected) {
                mismatches += "<$value> expected $expected but was ${actual.exceptionOrNull() ?: actual.getOrNull()}"
            }
        }
        assertTrue(
            "corpus size ${values.size}, mismatches ${mismatches.size}: ${mismatches.take(15)}",
            mismatches.isEmpty()
        )
    }

    private fun oracle(value: String): Long? {
        runCatching { Instant.parse(value).toEpochMilli() }.getOrNull()?.let { return it }
        runCatching { OffsetDateTime.parse(value).toInstant().toEpochMilli() }.getOrNull()?.let { return it }
        return runCatching {
            LocalDateTime.parse(value, ORACLE_LOCAL).toInstant(ZoneOffset.UTC).toEpochMilli()
        }.getOrNull()
    }

    private fun structuredCorpus(): List<String> {
        val values = ArrayList<String>()
        val years = listOf(0, 1, 999, 1899, 1900, 1969, 1970, 2000, 2024, 2025, 9999)
        val separators = listOf('T', 't', ' ')
        val fractions = listOf("", ".0", ".5", ".120", ".123456789", ".1234567890", ".")
        val suffixes = listOf(
            "", "Z", "z", "+00:00", "-00:00", "+05:30", "-05:30",
            "+18:00", "+18:01", "-18:00", "-18:30", "+19:00", "-19:00",
            "+00:60", "+0:00", "+00:000", "+0000", "ZZ", "Z ", " Z", "z+02:00"
        )
        years.forEach { year ->
            separators.forEach { separator ->
                fractions.forEach { fraction ->
                    suffixes.forEach { suffix ->
                        values += build(year, 2, 28, 13, 5, 7, separator, fraction, suffix)
                    }
                }
            }
        }
        listOf(29, 30, 31, 32, 0).forEach { day ->
            values += build(2024, 2, day, 0, 0, 0, 'T', "", "Z")
            values += build(2023, 2, day, 0, 0, 0, 'T', "", "Z")
            values += build(2100, 2, day, 0, 0, 0, 'T', "", "Z")
            values += build(2000, 2, day, 0, 0, 0, 'T', "", "Z")
        }
        listOf(4, 6, 9, 11).forEach { month ->
            values += build(2025, month, 31, 23, 59, 59, 'T', "", "Z")
        }
        listOf(24, 60, 99).forEach { hour ->
            values += build(2025, 1, 1, hour, 0, 0, 'T', "", "Z")
        }
        return values
    }

    private fun randomCorpus(random: Random, count: Int): List<String> {
        val values = ArrayList<String>(count)
        repeat(count) {
            val fraction = if (random.nextInt(4) == 0) {
                ""
            } else {
                "." + (1..random.nextInt(1, 13)).map { random.nextInt(0, 10) }.joinToString("")
            }
            values += build(
                random.nextInt(1, 10_000),
                random.nextInt(1, 13),
                random.nextInt(1, 32),
                random.nextInt(0, 24),
                random.nextInt(0, 60),
                random.nextInt(0, 61),
                listOf('T', 't', ' ').random(random),
                fraction,
                randomSuffix(random)
            )
        }
        return values
    }

    private fun randomSuffix(random: Random): String = when (random.nextInt(4)) {
        0 -> ""
        1 -> if (random.nextBoolean()) "Z" else "z"
        else -> {
            val sign = if (random.nextBoolean()) '+' else '-'
            val hour = random.nextInt(0, 20)
            val minute = random.nextInt(0, 60)
            "$sign%02d:%02d".format(Locale.ROOT, hour, minute)
        }
    }

    private fun mutatedCorpus(random: Random): List<String> {
        val seeds = listOf(
            "2025-02-02T17:28:22Z",
            "2025-02-02T17:28:22.123Z",
            "2025-02-02 17:28:22",
            "2025-02-02 17:28:22.987654321",
            "2025-02-02T17:28:22+02:00",
            "2025-02-02T17:28:22-05:30"
        )
        val replacements = charArrayOf('0', '9', 't', 'z', 'Z', 'T', ' ', ':', '-', '.', '+', 'x')
        val values = ArrayList<String>()
        seeds.forEach { seed ->
            values += seed
            values += seed + " "
            values += " " + seed
            values += seed.lowercase(Locale.ROOT)
            values += seed.uppercase(Locale.ROOT)
            values += seed.replace('T', ' ').replace('t', ' ')
            seed.indices.forEach { index ->
                values += seed.removeRange(index, index + 1)
                values += seed.substring(0, index) + replacements[random.nextInt(replacements.size)] + seed.substring(index + 1)
                values += seed.substring(0, index) + "0" + seed.substring(index)
            }
        }
        return values
    }

    private fun build(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        second: Int,
        separator: Char,
        fraction: String,
        suffix: String
    ): String = "%04d-%02d-%02d%s%02d:%02d:%02d%s%s".format(
        Locale.ROOT, year, month, day, separator, hour, minute, second, fraction, suffix
    )

    private companion object {
        const val RANDOM_COUNT = 3000

        val ORACLE_LOCAL = DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd HH:mm:ss")
            .optionalStart()
            .appendFraction(ChronoField.NANO_OF_SECOND, 1, 9, true)
            .optionalEnd()
            .toFormatter()
    }
}
