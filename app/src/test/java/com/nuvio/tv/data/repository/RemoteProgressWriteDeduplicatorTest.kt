package com.nuvio.tv.data.repository

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteProgressWriteDeduplicatorTest {
    @Test
    fun identicalProgressIsSuppressedInsideWindow() {
        val deduplicator = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        val progress = progress(position = 30_000L, lastWatched = 1_000L)

        assertTrue(deduplicator.shouldSend(1, "tt123_s1e1", progress, nowMs = 1_000L))
        assertFalse(
            deduplicator.shouldSend(
                1,
                "tt123_s1e1",
                progress.copy(lastWatched = 1_100L),
                nowMs = 1_100L
            )
        )
    }

    @Test
    fun changedProgressIsSentInsideWindow() {
        val deduplicator = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        val progress = progress(position = 30_000L, lastWatched = 1_000L)

        assertTrue(deduplicator.shouldSend(1, "tt123_s1e1", progress, nowMs = 1_000L))
        assertTrue(
            deduplicator.shouldSend(
                1,
                "tt123_s1e1",
                progress.copy(position = 31_000L, lastWatched = 1_100L),
                nowMs = 1_100L
            )
        )
    }

    @Test
    fun identicalProgressIsSentAfterWindow() {
        val deduplicator = RemoteProgressWriteDeduplicator(windowMs = 5_000L)
        val progress = progress(position = 30_000L, lastWatched = 1_000L)

        assertTrue(deduplicator.shouldSend(1, "tt123_s1e1", progress, nowMs = 1_000L))
        assertTrue(
            deduplicator.shouldSend(
                1,
                "tt123_s1e1",
                progress.copy(lastWatched = 6_000L),
                nowMs = 6_000L
            )
        )
    }

    private fun progress(position: Long, lastWatched: Long) = WatchProgress(
        contentId = "tt123",
        contentType = "series",
        name = "Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "tt123:1:1",
        season = 1,
        episode = 1,
        episodeTitle = "Episode",
        position = position,
        duration = 60_000L,
        lastWatched = lastWatched
    )
}
