package com.nuvio.tv.core.util

import com.nuvio.tv.domain.model.MetaReleaseDateCountry
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.Locale

// TMDB release types: https://developer.themoviedb.org/reference/movie-release-dates
private const val RELEASE_TYPE_THEATRICAL_LIMITED = 2
private const val RELEASE_TYPE_THEATRICAL = 3
private const val RELEASE_TYPE_DIGITAL = 4

// A typical theatrical run. Past this the movie is out of most cinemas even when the digital
// release is late or TMDB lacks it, so it no longer counts as in theaters.
private const val THEATRICAL_RUN_DAYS = 90L

data class MovieReleaseWindows(
    val theatrical: LocalDate?,
    val digital: LocalDate?
) {
    /** The digital release date while it is still ahead, so it can be announced. */
    fun upcomingDigital(today: LocalDate): LocalDate? = digital?.takeIf { it.isAfter(today) }
}

/**
 * Picks the theatrical and digital release dates, each from the first of [preferredRegions] that has
 * one, falling back to the earliest date in any region.
 */
fun resolveMovieReleaseWindows(
    releaseDates: List<MetaReleaseDateCountry>,
    preferredRegions: List<String>
): MovieReleaseWindows {
    if (releaseDates.isEmpty()) return MovieReleaseWindows(theatrical = null, digital = null)
    return MovieReleaseWindows(
        theatrical = pickReleaseDate(
            releaseDates,
            preferredRegions,
            setOf(RELEASE_TYPE_THEATRICAL_LIMITED, RELEASE_TYPE_THEATRICAL)
        ),
        digital = pickReleaseDate(releaseDates, preferredRegions, setOf(RELEASE_TYPE_DIGITAL))
    )
}

/**
 * True while a "Released" movie is only in cinemas: the theatrical release has happened, the digital
 * one has not, and the theatrical run is not over yet.
 */
fun isMovieInTheaters(
    rawStatus: String?,
    windows: MovieReleaseWindows,
    today: LocalDate
): Boolean {
    val status = rawStatus?.trim()?.lowercase(Locale.US)
    if (status != null && status != "released") return false
    if (windows.digital != null && !windows.digital.isAfter(today)) return false
    val theatrical = windows.theatrical ?: return false
    if (theatrical.isAfter(today)) return false
    return !theatrical.plusDays(THEATRICAL_RUN_DAYS).isBefore(today)
}

private fun pickReleaseDate(
    releaseDates: List<MetaReleaseDateCountry>,
    preferredRegions: List<String>,
    types: Set<Int>
): LocalDate? {
    fun MetaReleaseDateCountry.earliest(): LocalDate? = this.releaseDates
        .asSequence()
        .filter { it.type in types }
        .mapNotNull { parseReleaseDate(it.releaseDate) }
        .minOrNull()

    val byRegion = releaseDates.associateBy { it.countryCode.uppercase(Locale.US) }
    preferredRegions
        .map { it.uppercase(Locale.US) }
        .distinct()
        .forEach { region -> byRegion[region]?.earliest()?.let { return it } }
    return releaseDates.mapNotNull { it.earliest() }.minOrNull()
}

private fun parseReleaseDate(raw: String?): LocalDate? {
    val value = raw?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    // TMDB stores the calendar day at midnight UTC, so the date part is the release day everywhere.
    return runCatching { OffsetDateTime.parse(value).toLocalDate() }.getOrNull()
        ?: runCatching { LocalDate.parse(value.take(10)) }.getOrNull()
}
