package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Regression tests for currently-airing series leaving Continue Watching on air day.
 *
 * Scenario (Nuvio Sync, not Trakt):
 * 1. User finishes all *aired* episodes of a mid-season show → fully-watched checkmark
 * 2. Next episode is still unaired → may stay on CW as countdown next-up
 * 3. Episode airs same day → old code dropped the series from CW (fully-watched + hasAired)
 * 4. Fix keeps the series and clears the stale checkmark
 */
class ContinueWatchingAiringRulesTest {

    // --- Core decision (the air-day bug) ---

    @Test
    fun `fully watched with unaired next-up stays on CW and keeps badge`() {
        assertEquals(
            FullyWatchedNextUpAction.KEEP_WITH_BADGE,
            fullyWatchedNextUpAction(nextUpHasAired = false),
        )
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired = false))
    }

    @Test
    fun `fully watched with aired next-up stays on CW and clears badge`() {
        // This is the user-reported path: checkmark + new episode air day.
        assertEquals(
            FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE,
            fullyWatchedNextUpAction(nextUpHasAired = true),
        )
        assertFalse(
            "aired next-up must NOT drop fully-watched airing series from CW",
            shouldDropFullyWatchedNextUpFromContinueWatching(nextUpHasAired = true),
        )
    }

    @Test
    fun `legacy buggy rule would drop on air day — documents the regression`() {
        // 71e4fb6 / a58809 interaction: hasAired → drop.
        assertTrue(legacyShouldDropFullyWatchedNextUp(nextUpHasAired = true))
        assertFalse(legacyShouldDropFullyWatchedNextUp(nextUpHasAired = false))
        // Current rule must disagree with legacy on the air-day case.
        assertTrue(legacyShouldDropFullyWatchedNextUp(true))
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(true))
    }

    @Test
    fun `mid-season air day end-to-end pure flow`() {
        // Caught up through S1E5; badge cache only knows aired E1–E5.
        val airedBeforeDrop = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        val watched = setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5)
        assertTrue(isFullyWatchedAgainstAiredList(airedBeforeDrop, watched))

        // E6 airs. Next-up resolves as aired.
        val action = fullyWatchedNextUpAction(nextUpHasAired = true)
        assertEquals(FullyWatchedNextUpAction.KEEP_AND_CLEAR_BADGE, action)
        assertFalse(shouldDropFullyWatchedNextUpFromContinueWatching(true))

        // Badge cache absorbs E6 so publishBadgeUpdate does not re-checkmark.
        val airedAfter = mergeAiredNextEpisodeIntoBadgeCache(airedBeforeDrop, nextSeason = 1, nextEpisode = 6)!!
        assertEquals(
            setOf(1 to 1, 1 to 2, 1 to 3, 1 to 4, 1 to 5, 1 to 6),
            airedAfter,
        )
        assertFalse(
            "after E6 is known as aired and unwatched, series is no longer fully watched",
            isFullyWatchedAgainstAiredList(airedAfter, watched),
        )
    }

    // --- Badge cache merge ---

    @Test
    fun `merge aired next episode into empty cache returns null`() {
        assertNull(mergeAiredNextEpisodeIntoBadgeCache(null, 1, 6))
    }

    @Test
    fun `merge with missing season or episode leaves set unchanged`() {
        val existing = setOf(1 to 1, 1 to 2)
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, null, 6))
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, 1, null))
    }

    @Test
    fun `merge is idempotent when episode already present`() {
        val existing = setOf(1 to 1, 1 to 6)
        assertEquals(existing, mergeAiredNextEpisodeIntoBadgeCache(existing, 1, 6))
    }

    // --- Mid-season revalidation deadline ---

    @Test
    fun `earliestUpcomingEpisodeMs uses next mid-season air time not only next season`() {
        val now = Instant.parse("2026-07-12T12:00:00Z")
        val zone = ZoneId.systemDefault()
        val tomorrow = LocalDate.of(2026, 7, 13)
        val nextSeasonPremiere = LocalDate.of(2026, 9, 1)

        val meta = CwMetaSummary(
            id = "tt-test",
            name = "Airing Show",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, "2026-06-01"),
                episode(1, 2, "2026-06-08"),
                episode(1, 3, tomorrow.toString()), // mid-season next
                episode(2, 1, nextSeasonPremiere.toString()),
            ),
        )

        val ms = meta.earliestUpcomingEpisodeMs(now)
        assertNotNull(ms)
        val expected = tomorrow.atStartOfDay(zone).toInstant().toEpochMilli()
        assertEquals(expected, ms)

        // Revalidation should prefer the nearer mid-season episode over S2 premiere window.
        val revalidate = meta.earliestRevalidationMs(now)
        assertEquals(ms, revalidate)
    }

    @Test
    fun `earliestUpcomingEpisodeMs returns null when fully finished with no future dates`() {
        val now = Instant.parse("2026-07-12T12:00:00Z")
        val meta = CwMetaSummary(
            id = "tt-done",
            name = "Finished Show",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, "2025-01-01"),
                episode(1, 2, "2025-01-08"),
            ),
        )
        assertNull(meta.earliestUpcomingEpisodeMs(now))
    }

    @Test
    fun `watchableEpisodes excludes future mid-season episodes so checkmark can apply`() {
        // Documents a58809 behavior: only aired eps count → caught-up checkmark possible.
        val tomorrow = LocalDate.now(ZoneId.systemDefault()).plusDays(1).toString()
        val yesterday = LocalDate.now(ZoneId.systemDefault()).minusDays(1).toString()
        val meta = CwMetaSummary(
            id = "tt-airing",
            name = "Airing",
            poster = null,
            backdropUrl = null,
            logo = null,
            description = null,
            genres = emptyList(),
            releaseInfo = null,
            imdbRating = null,
            language = null,
            country = null,
            videos = listOf(
                episode(1, 1, yesterday),
                episode(1, 2, yesterday),
                episode(1, 3, tomorrow),
            ),
        )
        val watchable = meta.watchableEpisodes().map { it.season to it.episode }
        assertEquals(listOf(1 to 1, 1 to 2), watchable)
        // User watched all watchable → fully watched badge eligible, which then
        // used to trigger the CW drop on air day (covered above).
        assertTrue(
            isFullyWatchedAgainstAiredList(
                airedEpisodes = watchable.mapNotNull { (s, e) ->
                    if (s != null && e != null) s to e else null
                }.toSet(),
                watchedEpisodes = setOf(1 to 1, 1 to 2),
            ),
        )
    }

    private fun episode(season: Int, ep: Int, released: String) = CwVideoSummary(
        id = "tt-test:$season:$ep",
        title = "E$ep",
        released = released,
        thumbnail = null,
        season = season,
        episode = ep,
        overview = null,
    )
}
