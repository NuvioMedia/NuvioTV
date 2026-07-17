package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.TmdbSettings
import com.nuvio.tv.ui.screens.settings.TmdbSettingsUiState
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeReleaseDateParserTest {
    private val eastern = ZoneId.of("America/Detroit")

    @Test
    fun `utc timestamp uses the viewers local episode date`() {
        assertEquals(
            LocalDate.of(2026, 7, 15),
            parseEpisodeReleaseLocalDate("2026-07-16T00:00:00.000Z", eastern)
        )
    }

    @Test
    fun `offset timestamp is converted to the viewers timezone`() {
        assertEquals(
            LocalDate.of(2026, 7, 15),
            parseEpisodeReleaseLocalDate("2026-07-16T01:00:00+02:00", eastern)
        )
    }

    @Test
    fun `plain date and invalid value are handled`() {
        assertEquals(LocalDate.of(2026, 7, 16), parseEpisodeReleaseLocalDate("2026-07-16", eastern))
        assertNull(parseEpisodeReleaseLocalDate("not-a-date", eastern))
    }

    @Test
    fun `addon timestamp wins when tmdb release dates are disabled`() {
        assertEquals(
            "2026-07-15T15:00:00Z",
            selectEpisodeReleaseValue(
                addonReleased = "2026-07-15T15:00:00Z",
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = false
            )
        )
    }

    @Test
    fun `tmdb date replaces compliant addon timestamp only when enabled`() {
        assertEquals(
            "2026-07-16",
            selectEpisodeReleaseValue(
                addonReleased = "2026-07-15T15:00:00Z",
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = true
            )
        )
    }

    @Test
    fun `release selection falls back without inventing metadata`() {
        assertEquals(
            "2026-07-15T15:00:00Z",
            selectEpisodeReleaseValue(
                addonReleased = "2026-07-15T15:00:00Z",
                tmdbAirDate = null,
                useTmdbReleaseDates = true
            )
        )
        assertNull(
            selectEpisodeReleaseValue(
                addonReleased = null,
                tmdbAirDate = "2026-07-16",
                useTmdbReleaseDates = false
            )
        )
    }

    @Test
    fun `tmdb release dates default to disabled`() {
        assertFalse(TmdbSettings().useReleaseDates)
        assertFalse(TmdbSettingsUiState().useReleaseDates)
    }

    @Test
    fun `zoned release stays unavailable until its exact instant`() {
        val before = Clock.fixed(Instant.parse("2026-07-15T14:59:59Z"), eastern)
        val exact = Clock.fixed(Instant.parse("2026-07-15T15:00:00Z"), eastern)

        assertFalse(isEpisodeReleaseAired("2026-07-15T15:00:00Z", before)!!)
        assertTrue(isEpisodeReleaseAired("2026-07-15T15:00:00Z", exact)!!)
    }

    @Test
    fun `date only release starts at utc midnight`() {
        val beforeMidnight = Clock.fixed(Instant.parse("2026-07-14T23:59:59Z"), eastern)
        val utcMidnight = Clock.fixed(Instant.parse("2026-07-15T00:00:00Z"), eastern)

        assertFalse(isEpisodeReleaseAired("2026-07-15", beforeMidnight)!!)
        assertTrue(isEpisodeReleaseAired("2026-07-15", utcMidnight)!!)
    }

    @Test
    fun `local timestamp uses viewer timezone without overriding tmdb`() {
        val before = Clock.fixed(Instant.parse("2026-07-15T18:59:59Z"), eastern)
        val exact = Clock.fixed(Instant.parse("2026-07-15T19:00:00Z"), eastern)

        assertFalse(isEpisodeReleaseAired("2026-07-15T15:00:00", before)!!)
        assertTrue(isEpisodeReleaseAired("2026-07-15T15:00:00", exact)!!)
    }

    @Test
    fun `invalid and missing release eligibility remain unknown`() {
        assertNull(isEpisodeReleaseAired(null))
        assertNull(isEpisodeReleaseAired("not-a-date"))
    }
}
