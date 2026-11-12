package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.NextEpisodeThresholdMode
import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PostPlayRecommendationTimingTest {

    private val durationMs = 7_200_000L // 2 hours
    private val movieThresholdPercent = 96 // 6_912_000 ms

    private fun showsAt(
        positionMs: Long,
        intervals: List<SkipInterval>,
        thresholdPercent: Int = movieThresholdPercent
    ): Boolean =
        shouldShowPostPlayRecommendation(
            contentType = "movie",
            positionMs = positionMs,
            durationMs = durationMs,
            skipIntervals = intervals,
            movieThresholdPercent = thresholdPercent,
            episodeThresholdMode = NextEpisodeThresholdMode.PERCENTAGE,
            episodeThresholdPercent = 99f, // 72 s before the end
            episodeThresholdMinutesBeforeEnd = 2f
        )

    private fun interval(startSec: Double, endSec: Double, type: String, provider: String) =
        SkipInterval(startTime = startSec, endTime = endSec, type = type, provider = provider)

    @Test
    fun `movie without skip data uses the movie threshold`() {
        assertFalse(showsAt(6_911_000L, emptyList()))
        assertTrue(showsAt(6_912_000L, emptyList()))
    }

    @Test
    fun `End Credits chapter at the end of a movie triggers at the credits start`() {
        val chapters = PlayerChapters.normalize(
            listOf(
                PlayerChapter(0L, "Chapter 1"),
                PlayerChapter(3_600_000L, "Chapter 2"),
                PlayerChapter(6_990_000L, "End Credits")
            )
        )
        val intervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)

        assertFalse(showsAt(6_950_000L, intervals))
        assertTrue(showsAt(6_990_000L, intervals))
    }

    @Test
    fun `chapter credits starting before the movie threshold trigger earlier`() {
        // 98% would be 7_056_000 ms.
        val intervals = listOf(interval(6_990.0, 7_200.0, "outro", "chapters"))

        assertFalse(showsAt(6_989_000L, intervals, thresholdPercent = 98))
        assertTrue(showsAt(6_990_000L, intervals, thresholdPercent = 98))
    }

    @Test
    fun `chapter credits followed by a long tail wait for the episode threshold`() {
        // A post-credits chapter is excluded from skip intervals, leaving 200 s after the credits.
        val intervals = listOf(interval(6_800.0, 7_000.0, "outro", "chapters"))

        assertFalse(showsAt(7_127_000L, intervals))
        assertTrue(showsAt(7_128_000L, intervals))
    }

    @Test
    fun `credits chapter in the first half of a movie is ignored`() {
        val intervals = listOf(interval(0.0, 120.0, "outro", "chapters"))

        assertFalse(showsAt(6_911_000L, intervals))
        assertTrue(showsAt(6_912_000L, intervals))
    }

    @Test
    fun `IntroDB movie credits still trigger at the credits start`() {
        val intervals = listOf(interval(6_990.0, 7_200.0, "movie-credits", "introdb"))

        assertFalse(showsAt(6_950_000L, intervals))
        assertTrue(showsAt(6_990_000L, intervals))
    }

    @Test
    fun `prefetch follows the chapter credits start`() {
        val progress = postPlayRecommendationPrefetchProgress(
            contentType = "movie",
            movieThresholdPercent = movieThresholdPercent,
            durationMs = durationMs,
            skipIntervals = listOf(interval(6_990.0, 7_200.0, "outro", "chapters"))
        )

        assertEquals(6_990_000.0 / durationMs - 0.05, progress.toDouble(), 0.0001)
    }
}
