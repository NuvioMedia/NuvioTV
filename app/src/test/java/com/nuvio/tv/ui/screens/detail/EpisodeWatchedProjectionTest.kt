package com.nuvio.tv.ui.screens.detail

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
}
