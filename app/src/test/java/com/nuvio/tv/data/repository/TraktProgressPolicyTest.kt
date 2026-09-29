package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktProgressPolicyTest {
    @Test
    fun `continue watching cutoff preserves all and computes bounded windows`() {
        val now = 10_000_000_000L

        assertNull(
            traktContinueWatchingCutoffEpochMs(
                TraktSettingsDataStore.CONTINUE_WATCHING_DAYS_CAP_ALL,
                now
            )
        )
        assertEquals(
            now - 60L * 24L * 60L * 60L * 1_000L,
            traktContinueWatchingCutoffEpochMs(60, now)
        )
    }

    @Test
    fun `history rows are valid completed next up seeds`() {
        val now = 1_000_000L
        val progress = progress(
            source = WatchProgress.SOURCE_TRAKT_HISTORY,
            percent = 100f,
            lastWatched = 1L
        )

        assertTrue(shouldUseTraktNextUpSeed(progress, now))
    }

    @Test
    fun `playback seeds require explicit completion and recency`() {
        val now = 1_000_000L

        assertTrue(
            shouldUseTraktNextUpSeed(
                progress(WatchProgress.SOURCE_TRAKT_PLAYBACK, 95f, now - 10_000L),
                now
            )
        )
        assertFalse(
            shouldUseTraktNextUpSeed(
                progress(WatchProgress.SOURCE_TRAKT_PLAYBACK, 94f, now - 10_000L),
                now
            )
        )
        assertFalse(
            shouldUseTraktNextUpSeed(
                progress(WatchProgress.SOURCE_TRAKT_PLAYBACK, 100f, now - 180_001L),
                now
            )
        )
        assertFalse(
            shouldUseTraktNextUpSeed(
                progress(WatchProgress.SOURCE_TRAKT_PLAYBACK, 100f, now + 1L),
                now
            )
        )
    }

    @Test
    fun `active Trakt normalization derives canonical ids from video ids`() {
        assertEquals("tt7821582", resolveEffectiveContentId("wrapped", "tt7821582:3:7"))
        assertEquals("tmdb:123", resolveEffectiveContentId("wrapped", "tmdb:123"))
        assertEquals("tt100", resolveEffectiveContentId("tt100", "tt200:1:1"))
        assertEquals("wrapped", resolveEffectiveContentId("wrapped", "kitsu:44"))
    }

    @Test
    fun `episode lookup uses aggregate progress while the episode snapshot loads`() {
        val aggregateProgress = progress(
            source = WatchProgress.SOURCE_TRAKT_PLAYBACK,
            percent = 48f,
            lastWatched = 100L
        )

        assertEquals(
            aggregateProgress,
            resolveProviderEpisodeProgress(
                contentId = "tt123",
                season = 1,
                episode = 1,
                episodeProgress = emptyMap(),
                allProgress = listOf(aggregateProgress)
            )
        )
    }

    @Test
    fun `Trakt retains progress whose ids cannot be represented remotely`() {
        assertTrue(shouldRetainTraktLocalProgress("kitsu:44"))
        assertTrue(shouldRetainTraktLocalProgress("mal:21"))
        assertTrue(shouldRetainTraktLocalProgress("anilist:101"))
        assertFalse(shouldRetainTraktLocalProgress("tt7821582"))
        assertFalse(shouldRetainTraktLocalProgress("tmdb:123"))
        assertFalse(shouldRetainTraktLocalProgress("trakt:456"))
    }

    @Test
    fun `Trakt retains locally completed series episodes`() {
        assertTrue(shouldRetainTraktLocalWatchedEpisode(watchedItem("series", 1, 2)))
        assertTrue(shouldRetainTraktLocalWatchedEpisode(watchedItem("TV", 1, 2)))
        assertFalse(shouldRetainTraktLocalWatchedEpisode(watchedItem("movie", null, null)))
        assertFalse(shouldRetainTraktLocalWatchedEpisode(watchedItem("series", null, null)))
    }

    private fun progress(source: String, percent: Float, lastWatched: Long) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Series",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt123:1:1",
        season = 1,
        episode = 1,
        episodeTitle = null,
        position = 0L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = percent,
        source = source
    )

    private fun watchedItem(contentType: String, season: Int?, episode: Int?) = WatchedItem(
        contentId = "tt123",
        contentType = contentType,
        title = "Series",
        season = season,
        episode = episode,
        watchedAt = 1L
    )
}
