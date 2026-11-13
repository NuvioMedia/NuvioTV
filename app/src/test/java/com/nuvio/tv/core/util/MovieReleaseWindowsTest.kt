package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.MetaReleaseDate
import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MovieReleaseWindowsTest {
    private val today = LocalDate.of(2026, 5, 1)

    private fun country(code: String, vararg dates: Pair<Int, String>) = MetaReleaseDateCountry(
        countryCode = code,
        releaseDates = dates.map { (type, date) -> MetaReleaseDate(releaseDate = date, type = type) }
    )

    @Test
    fun `preferred region wins over earlier dates elsewhere`() {
        val windows = resolveMovieReleaseWindows(
            listOf(
                country("FR", 3 to "2026-02-01T00:00:00.000Z", 4 to "2026-03-01T00:00:00.000Z"),
                country("HR", 3 to "2026-02-20T00:00:00.000Z", 4 to "2026-04-15T00:00:00.000Z")
            ),
            preferredRegions = listOf("hr", "US")
        )
        assertEquals(LocalDate.of(2026, 2, 20), windows.theatrical)
        assertEquals(LocalDate.of(2026, 4, 15), windows.digital)
    }

    @Test
    fun `falls back per type to the earliest date in any region`() {
        val windows = resolveMovieReleaseWindows(
            listOf(
                country("US", 3 to "2026-02-10T00:00:00.000Z"),
                country("GB", 4 to "2026-04-02T00:00:00.000Z"),
                country("DE", 4 to "2026-03-30T00:00:00.000Z")
            ),
            preferredRegions = listOf("US")
        )
        assertEquals(LocalDate.of(2026, 2, 10), windows.theatrical)
        assertEquals(LocalDate.of(2026, 3, 30), windows.digital)
    }

    @Test
    fun `limited theatrical counts and premieres and physical do not`() {
        val windows = resolveMovieReleaseWindows(
            listOf(
                country(
                    "US",
                    1 to "2025-09-01T00:00:00.000Z",
                    2 to "2025-12-20T00:00:00.000Z",
                    3 to "2026-01-10T00:00:00.000Z",
                    5 to "2026-03-01T00:00:00.000Z"
                )
            ),
            preferredRegions = listOf("US")
        )
        assertEquals(LocalDate.of(2025, 12, 20), windows.theatrical)
        assertNull(windows.digital)
    }

    @Test
    fun `theatrical release before digital is in theaters`() {
        val windows = MovieReleaseWindows(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 1))
        assertTrue(isMovieInTheaters("Released", windows, today))
    }

    @Test
    fun `digital release ends the theatrical window`() {
        val windows = MovieReleaseWindows(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 20))
        assertFalse(isMovieInTheaters("Released", windows, today))
    }

    @Test
    fun `long theatrical run without digital release is no longer in theaters`() {
        assertFalse(isMovieInTheaters("Released", MovieReleaseWindows(LocalDate.of(2026, 1, 15), LocalDate.of(2026, 8, 1)), today))
        assertFalse(isMovieInTheaters("Released", MovieReleaseWindows(LocalDate.of(1999, 3, 31), null), today))
    }

    @Test
    fun `unreleased or non released statuses are not in theaters`() {
        assertFalse(isMovieInTheaters("Released", MovieReleaseWindows(LocalDate.of(2026, 6, 1), null), today))
        assertFalse(isMovieInTheaters("Post Production", MovieReleaseWindows(LocalDate.of(2026, 4, 1), null), today))
    }

    @Test
    fun `only a digital release that is still ahead is announced`() {
        assertEquals(
            LocalDate.of(2026, 6, 1),
            MovieReleaseWindows(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 6, 1)).upcomingDigital(today)
        )
        assertNull(MovieReleaseWindows(LocalDate.of(2026, 2, 1), LocalDate.of(2026, 4, 20)).upcomingDigital(today))
        assertNull(MovieReleaseWindows(LocalDate.of(2026, 2, 1), null).upcomingDigital(today))
    }
}
