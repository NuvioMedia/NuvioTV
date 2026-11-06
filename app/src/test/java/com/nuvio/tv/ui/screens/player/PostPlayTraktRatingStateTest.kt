package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayTraktRatingStateTest {

    @Test
    fun `only movies with a trakt resolvable id become rating targets`() {
        val movie = TrackingMediaReference(
            kind = TrackingMediaKind.MOVIE,
            title = "Dune: Part Two",
            year = 2024,
            ids = TrackingExternalIds(imdb = "tt15239678", tmdb = 693134L)
        )
        val target = movie.toPostPlayTraktRatingTarget()
        assertNotNull(target)
        assertEquals("tt15239678", target?.ids?.imdb)
        assertEquals(693134, target?.ids?.tmdb)

        val episode = movie.copy(kind = TrackingMediaKind.SHOW, episode = TrackingEpisode(season = 1, number = 1))
        assertNull(episode.toPostPlayTraktRatingTarget())

        val simklOnly = movie.copy(ids = TrackingExternalIds(simkl = 42L))
        assertNull(simklOnly.toPostPlayTraktRatingTarget())
    }

    @Test
    fun `stepping stays within the trakt rating range`() {
        assertEquals(7, stepPostPlayTraktRating(6, 1))
        assertEquals(10, stepPostPlayTraktRating(10, 1))
        assertEquals(1, stepPostPlayTraktRating(1, -1))
    }

    @Test
    fun `changing the selection after a failure clears the error`() {
        val failed = PostPlayTraktRatingUiState(isVisible = true, selectedRating = 8, status = PostPlayTraktRatingStatus.FAILED)

        val updated = failed.withSelection(9)

        assertEquals(9, updated.selectedRating)
        assertEquals(PostPlayTraktRatingStatus.IDLE, updated.status)
    }

    @Test
    fun `selection is locked while saving`() {
        val saving = PostPlayTraktRatingUiState(isVisible = true, selectedRating = 8, status = PostPlayTraktRatingStatus.SAVING)

        assertEquals(saving, saving.withSelection(9))
        assertFalse(saving.canSubmit())
    }

    @Test
    fun `an already saved rating cannot be resubmitted until it changes`() {
        val saved = PostPlayTraktRatingUiState(isVisible = true, selectedRating = 8, savedRating = 8)
        assertTrue(saved.isSelectionSaved)
        assertFalse(saved.canSubmit())

        assertTrue(saved.withSelection(7).canSubmit())
        assertFalse(PostPlayTraktRatingUiState(isVisible = false).canSubmit())
    }

    @Test
    fun `prompt opens for movies at the post-play threshold or when playback ends`() {
        val durationMs = 120 * 60_000L
        assertFalse(prompt(contentType = "movie", positionMs = durationMs / 2, durationMs = durationMs))
        assertTrue(prompt(contentType = "movie", positionMs = durationMs - 1_000L, durationMs = durationMs))
        assertTrue(prompt(contentType = "movie", positionMs = durationMs / 2, durationMs = durationMs, playbackEnded = true))
    }

    @Test
    fun `prompt never opens for series or short placeholder videos`() {
        val durationMs = 45 * 60_000L
        assertFalse(prompt(contentType = "series", positionMs = durationMs, durationMs = durationMs, playbackEnded = true))
        assertFalse(prompt(contentType = "movie", positionMs = 60_000L, durationMs = 60_000L, playbackEnded = true))
    }

    private fun prompt(
        contentType: String,
        positionMs: Long,
        durationMs: Long,
        playbackEnded: Boolean = false
    ): Boolean = shouldPromptPostPlayTraktRating(
        contentType = contentType,
        playbackEnded = playbackEnded,
        positionMs = positionMs,
        durationMs = durationMs,
        skipIntervals = emptyList(),
        movieThresholdPercent = 95,
        episodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
        episodeThresholdPercent = 98f,
        episodeThresholdMinutesBeforeEnd = 2f
    )
}
