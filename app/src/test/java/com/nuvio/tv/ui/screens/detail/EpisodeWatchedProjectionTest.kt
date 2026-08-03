package com.nuvio.tv.ui.screens.detail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EpisodeWatchedProjectionTest {
    @Test
    fun `unsupported video id resolution preserves Nuvio Sync watched state`() {
        assertTrue(
            resolveEpisodeWatchedState(
                currentlyWatched = true,
                completedByProgress = false,
                optimisticallyMarked = false,
                optimisticallyUnmarked = false,
                watchedByVideoId = null
            )
        )
    }

    @Test
    fun `unsupported video id resolution does not create watched state`() {
        assertFalse(
            resolveEpisodeWatchedState(
                currentlyWatched = false,
                completedByProgress = false,
                optimisticallyMarked = false,
                optimisticallyUnmarked = false,
                watchedByVideoId = null
            )
        )
    }

    @Test
    fun `provider video id match adds watched episode`() {
        assertTrue(
            resolveEpisodeWatchedState(
                currentlyWatched = false,
                completedByProgress = false,
                optimisticallyMarked = false,
                optimisticallyUnmarked = false,
                watchedByVideoId = true
            )
        )
    }

    @Test
    fun `provider video id miss removes stale local episode`() {
        assertFalse(
            resolveEpisodeWatchedState(
                currentlyWatched = true,
                completedByProgress = false,
                optimisticallyMarked = false,
                optimisticallyUnmarked = false,
                watchedByVideoId = false
            )
        )
    }

    @Test
    fun `completed progress survives provider video id miss`() {
        assertTrue(
            resolveEpisodeWatchedState(
                currentlyWatched = true,
                completedByProgress = true,
                optimisticallyMarked = false,
                optimisticallyUnmarked = false,
                watchedByVideoId = false
            )
        )
    }

    @Test
    fun `optimistic watched changes win over provider resolution`() {
        assertTrue(
            resolveEpisodeWatchedState(
                currentlyWatched = true,
                completedByProgress = false,
                optimisticallyMarked = true,
                optimisticallyUnmarked = false,
                watchedByVideoId = false
            )
        )
        assertFalse(
            resolveEpisodeWatchedState(
                currentlyWatched = false,
                completedByProgress = false,
                optimisticallyMarked = false,
                optimisticallyUnmarked = true,
                watchedByVideoId = true
            )
        )
    }

    @Test
    fun `detail watched lookup keeps navigation id when effective id is canonical imdb`() {
        // Repro for #2883: CW/mobile store history under tmdb:/catalog id while
        // details switches effective id to tt… and previously lost episode ticks.
        assertEquals(
            listOf("tt1234567", "tmdb:999", "tt9999999"),
            detailWatchedContentIds(
                navigationItemId = "tmdb:999",
                effectiveContentId = "tt1234567",
                metaId = "tt1234567",
                metaImdbId = "tt9999999"
            )
        )
    }

    @Test
    fun `detail watched lookup dedupes blank and identical ids`() {
        assertEquals(
            listOf("tt1", "tmdb:2"),
            detailWatchedContentIds(
                navigationItemId = " tmdb:2 ",
                effectiveContentId = "tt1",
                metaId = "tt1",
                metaImdbId = "  "
            )
        )
    }

    @Test
    fun `merge episode key sets unions sibling content id history`() {
        val underTmdb = setOf(1 to 1, 1 to 2)
        val underImdb = setOf(1 to 2, 1 to 3)
        assertEquals(
            setOf(1 to 1, 1 to 2, 1 to 3),
            mergeEpisodeKeySets(listOf(underTmdb, underImdb))
        )
    }
}
