package com.nuvio.tv.data.local

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressBucketsTest {
    @Test
    fun `split keeps only the newest 200 entries in recent`() {
        val entries = (0 until 250).associate { index ->
            "item$index" to progress("item$index", lastWatched = index.toLong())
        }

        val buckets = splitWatchProgressEntries(entries)

        assertEquals(200, buckets.recent.size)
        assertEquals(50, buckets.archive.size)
        assertTrue("item249" in buckets.recent)
        assertTrue("item50" in buckets.recent)
        assertTrue("item49" in buckets.archive)
        assertFalse("item50" in buckets.archive)
    }

    @Test
    fun `merge lets recent entries override archive duplicates`() {
        val archiveItem = progress("item", position = 100L, lastWatched = 1L)
        val recentItem = progress("item", position = 200L, lastWatched = 2L)

        val merged = mergeWatchProgressBuckets(
            recent = mapOf("item" to recentItem),
            archive = mapOf("item" to archiveItem)
        )

        assertEquals(1, merged.size)
        assertEquals(recentItem, merged["item"])
    }

    @Test
    fun `split removes legacy series mirror when episode entries exist`() {
        val mirror = progress("series", lastWatched = 1L)
        val episode = progress(
            contentId = "series",
            season = 1,
            episode = 2,
            lastWatched = 2L
        )

        val buckets = splitWatchProgressEntries(
            mapOf(
                "series" to mirror,
                "series_s1e2" to episode
            )
        )
        val merged = mergeWatchProgressBuckets(buckets.recent, buckets.archive)

        assertFalse("series" in merged)
        assertEquals(episode, merged["series_s1e2"])
    }

    private fun progress(
        contentId: String,
        position: Long = 1_000L,
        season: Int? = null,
        episode: Int? = null,
        lastWatched: Long
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
        duration = 10_000L,
        lastWatched = lastWatched
    )
}
