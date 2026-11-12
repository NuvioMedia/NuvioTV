package com.nuvio.tv.data.simkl

import java.util.Locale
import kotlin.random.Random
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklTimestampDifferentialTest {

    @Test
    fun `manual parser agrees with legacy regex parser on structured corpus`() {
        assertMatchesLegacy(structuredCorpus())
    }

    @Test
    fun `manual parser agrees with legacy regex parser on randomized corpus`() {
        assertMatchesLegacy(randomCorpus(Random(19_701)))
    }

    @Test
    fun `manual parser agrees with legacy regex parser on mutated corpus`() {
        assertMatchesLegacy(mutatedCorpus(Random(4_242)))
    }

    private fun assertMatchesLegacy(values: List<String?>) {
        val mismatches = ArrayList<String>()
        values.forEach { value ->
            val expected = legacyParseSimklUtcEpochMs(value)
            val actual = parseSimklUtcEpochMs(value)
            if (expected != actual) {
                mismatches += "<$value> legacy=$expected manual=$actual"
            }
        }
        assertTrue(
            "corpus size ${values.size}, mismatches ${mismatches.size}: ${mismatches.take(15)}",
            mismatches.isEmpty()
        )
    }

    private fun structuredCorpus(): List<String?> {
        val values = ArrayList<String?>()
        values += null
        values += ""
        values += "   "
        val years = listOf(0, 1, 1969, 1970, 1971, 1999, 2000, 2023, 2024, 2100, 9999)
        val separators = listOf('T', 't', ' ')
        val fractions = listOf("", ".0", ".5", ".12", ".123", ".123456789", ".1234567890", ".")
        val suffixes = listOf("", "Z", "z", "ZZ", "z ", " z", "+00:00", "\n")
        years.forEach { year ->
            separators.forEach { separator ->
                fractions.forEach { fraction ->
                    suffixes.forEach { suffix ->
                        values += build(year, 2, 29, 23, 59, 59, separator, fraction, suffix)
                    }
                }
            }
        }
        listOf(0, 1, 28, 29, 30, 31, 32).forEach { day ->
            values += build(2023, 2, day, 1, 2, 3, 'T', "", "Z")
            values += build(2024, 2, day, 1, 2, 3, 'T', "", "Z")
            values += build(1900, 2, day, 1, 2, 3, 'T', "", "Z")
            values += build(2000, 2, day, 1, 2, 3, 'T', "", "Z")
        }
        listOf(0, 13, 99).forEach { month ->
            values += build(2025, month, 1, 1, 2, 3, 'T', "", "Z")
        }
        listOf(23, 24, 99).forEach { hour ->
            values += build(2025, 1, 1, hour, 0, 0, 'T', "", "Z")
        }
        listOf(59, 60, 99).forEach { second ->
            values += build(2025, 1, 1, 0, 0, second, 'T', "", "Z")
        }
        return values
    }

    private fun randomCorpus(random: Random): List<String?> {
        val values = ArrayList<String?>()
        repeat(RANDOM_COUNT) {
            val fraction = if (random.nextInt(3) == 0) {
                ""
            } else {
                "." + (1..random.nextInt(1, 12)).map { random.nextInt(0, 10) }.joinToString("")
            }
            values += build(
                random.nextInt(1960, 10_000),
                random.nextInt(0, 14),
                random.nextInt(0, 33),
                random.nextInt(0, 25),
                random.nextInt(0, 61),
                random.nextInt(0, 61),
                listOf('T', 't', ' ').random(random),
                fraction,
                listOf("", "Z", "z", "Z ", "ZZ").random(random)
            )
        }
        return values
    }

    private fun mutatedCorpus(random: Random): List<String?> {
        val seeds = listOf(
            "2025-02-02T17:28:22Z",
            "2025-02-02T17:28:22.123Z",
            "1970-01-01T00:00:00Z",
            "2024-02-29t00:00:00.5z"
        )
        val replacements = charArrayOf('0', '9', 't', 'z', 'Z', 'T', ' ', ':', '-', '.', '+', 'x', '\n')
        val values = ArrayList<String?>()
        seeds.forEach { seed ->
            values += seed
            values += seed + " "
            values += " " + seed
            values += seed.uppercase(Locale.ROOT)
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

    private fun legacyParseSimklUtcEpochMs(value: String?): Long? {
        val match = value?.trim()?.let(LEGACY_PATTERN::matchEntire) ?: return null
        val year = match.groupValues[1].toIntOrNull() ?: return null
        val month = match.groupValues[2].toIntOrNull() ?: return null
        val day = match.groupValues[3].toIntOrNull() ?: return null
        val hour = match.groupValues[4].toIntOrNull() ?: return null
        val minute = match.groupValues[5].toIntOrNull() ?: return null
        val second = match.groupValues[6].toIntOrNull() ?: return null
        val millis = match.groupValues[7].padEnd(3, '0').take(3).toIntOrNull() ?: 0
        if (year < 1970 || month !in 1..12 || hour !in 0..23 || minute !in 0..59 || second !in 0..59) return null
        val monthDays = legacyDaysInMonths(year)
        if (day !in 1..monthDays[month - 1]) return null
        var days = 0L
        for (currentYear in 1970 until year) days += if (legacyIsLeapYear(currentYear)) 366L else 365L
        for (monthIndex in 0 until month - 1) days += monthDays[monthIndex]
        days += day - 1L
        return (((days * 24L + hour) * 60L + minute) * 60L + second) * 1_000L + millis
    }

    private fun legacyIsLeapYear(year: Int): Boolean =
        (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

    private fun legacyDaysInMonths(year: Int): IntArray = intArrayOf(
        31, if (legacyIsLeapYear(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31
    )

    private companion object {
        const val RANDOM_COUNT = 3000

        val LEGACY_PATTERN = Regex(
            "^(\\d{4})-(\\d{2})-(\\d{2})T(\\d{2}):(\\d{2}):(\\d{2})(?:\\.(\\d{1,9}))?Z$",
            RegexOption.IGNORE_CASE
        )
    }
}
