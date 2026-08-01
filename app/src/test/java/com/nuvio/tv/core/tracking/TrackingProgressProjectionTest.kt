package com.nuvio.tv.core.tracking

import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Test

class TrackingProgressProjectionTest {
    @Test
    fun `projection keeps eligible local entries without replacing provider in-progress`() {
        val providerEntry = progress("tt100", 1, 1, 10L, 25L)
        val duplicateLocal = progress("tt100", 1, 1, 30L, 75L)
        val retainedLocal = progress("kitsu:44", 1, 2, 20L, 50L)
        val excludedCompletedLocal = progress("tt200", null, null, 40L, 95L, duration = 100L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(duplicateLocal, retainedLocal, excludedCompletedLocal),
            retainsLocalProgress = { contentId -> contentId.startsWith("kitsu:") }
        )

        // Provider in-progress wins over duplicate local; unsupported local kept; completed local dropped.
        assertEquals(listOf(retainedLocal, providerEntry), result)
    }

    @Test
    fun `projection retains in-progress local even when retain policy is false`() {
        val providerEntry = progress("simkl:100", null, null, 10L, 25L)
        val localEntry = progress("kitsu:44", null, null, 20L, 50L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(localEntry),
            retainsLocalProgress = { false }
        )

        // Local is still in-progress so it fills the provider gap (#2716).
        assertEquals(listOf(localEntry, providerEntry), result)
    }

    @Test
    fun `local in-progress fills gap when provider has no row for that episode`() {
        val providerOther = progress("tt100", 1, 1, 10L, 25L)
        val localInProgress = progress("tt200", 2, 3, 50L, 40L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerOther),
            localEntries = listOf(localInProgress),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(localInProgress, providerOther), result)
    }

    @Test
    fun `local in-progress replaces completed-only remote for same episode`() {
        val remoteCompleted = progress("tt100", 1, 5, 10L, 100L, duration = 100L)
        val localRewatch = progress("tt100", 1, 5, 20L, 40L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(remoteCompleted),
            localEntries = listOf(localRewatch),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(localRewatch), result)
    }

    @Test
    fun `local completed is not retained without retain policy`() {
        val providerEntry = progress("tt100", 1, 1, 10L, 25L)
        val localCompleted = progress("tt200", 1, 1, 50L, 100L, duration = 100L)

        val result = mergeProgressProjectionWithRetainedLocal(
            providerEntries = listOf(providerEntry),
            localEntries = listOf(localCompleted),
            retainsLocalProgress = { false }
        )

        assertEquals(listOf(providerEntry), result)
    }

    @Test
    fun `watched episode projection unions only provider retained local episodes`() {
        val providerEpisodes = mapOf("tt100" to setOf(1 to 1))
        val retained = watchedItem("tt100", "series", 1, 2)
        val retainedShow = watchedItem("tt200", "tv", 2, 3)
        val excluded = watchedItem("tt300", "movie", null, null)

        val result = mergeWatchedEpisodeProjection(
            providerEpisodes = providerEpisodes,
            localItems = listOf(retained, retainedShow, excluded),
            retainsLocalWatchedEpisode = { item -> item.contentType != "movie" }
        )

        assertEquals(setOf(1 to 1, 1 to 2), result["tt100"])
        assertEquals(setOf(2 to 3), result["tt200"])
        assertEquals(null, result["tt300"])
    }

    private fun progress(
        contentId: String,
        season: Int?,
        episode: Int?,
        lastWatched: Long,
        position: Long,
        duration: Long = 100L
    ) = WatchProgress(
        contentId = contentId,
        contentType = if (season == null) "movie" else "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched
    )

    private fun watchedItem(
        contentId: String,
        contentType: String,
        season: Int?,
        episode: Int?
    ) = WatchedItem(
        contentId = contentId,
        contentType = contentType,
        title = contentId,
        season = season,
        episode = episode,
        watchedAt = 1L
    )
}
