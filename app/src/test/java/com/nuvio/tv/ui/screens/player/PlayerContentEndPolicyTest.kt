package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the content ends for the tracker: where the content end marker is read and when it is refused.
 *
 * The value is a percentage, not a time, because that is the number both the tracker and the local
 * Continue Watching row compare, and it is the same value mobile reads from `SkipModels.kt`.
 */
class PlayerContentEndPolicyTest {

    @Test
    fun `the outro marker says where the content ends`() {
        val intervals = listOf(interval(type = "outro", startTime = 90.0))

        assertEquals(90.0, intervals.contentEndPercent(durationMs = 100_000L)!!, 1e-9)
    }

    @Test
    fun `the film credits marker says where the content ends`() {
        val intervals = listOf(interval(type = "movie-credits", startTime = 3_600.0))

        assertEquals(
            90.0,
            intervals.contentEndPercent(durationMs = 4_000_000L)!!,
            1e-9
        )
    }

    @Test
    fun `the anime outro types count as the end of the content too`() {
        assertEquals(
            80.0,
            listOf(interval(type = "ed", startTime = 80.0)).contentEndPercent(100_000L)!!,
            1e-9
        )
        assertEquals(
            80.0,
            listOf(interval(type = "mixed-ed", startTime = 80.0)).contentEndPercent(100_000L)!!,
            1e-9
        )
    }

    @Test
    fun `the earliest marker wins, because that is where the content really stops`() {
        val intervals = listOf(
            interval(type = "outro", startTime = 95.0),
            interval(type = "movie-credits", startTime = 90.0)
        )

        assertEquals(90.0, intervals.contentEndPercent(100_000L)!!, 1e-9)
    }

    @Test
    fun `a post credits scene never moves the end of the content later`() {
        val intervals = listOf(
            interval(type = "outro", startTime = 90.0),
            interval(type = "post-credits", startTime = 95.0)
        )

        assertEquals(90.0, intervals.contentEndPercent(100_000L)!!, 1e-9)
    }

    @Test
    fun `an intro or a scene alone is not a content end`() {
        assertNull(listOf(interval(type = "intro", startTime = 5.0)).contentEndPercent(100_000L))
        assertNull(listOf(interval(type = "recap", startTime = 5.0)).contentEndPercent(100_000L))
        assertNull(
            listOf(interval(type = "post-credits", startTime = 95.0)).contentEndPercent(100_000L)
        )
        assertNull(emptyList<SkipInterval>().contentEndPercent(100_000L))
    }

    @Test
    fun `a duration that is not a real playback answers nothing`() {
        val intervals = listOf(interval(type = "outro", startTime = 90.0))

        assertNull(intervals.contentEndPercent(durationMs = 0L))
        assertNull(intervals.contentEndPercent(durationMs = -1_000L))
    }

    @Test
    fun `a marker outside the video answers nothing`() {
        val intervals = listOf(interval(type = "outro", startTime = 120.0))

        assertNull(intervals.contentEndPercent(durationMs = 100_000L))
    }

    @Test
    fun `a marker at the very start of the video answers nothing`() {
        assertNull(listOf(interval(type = "outro", startTime = 0.0)).contentEndPercent(100_000L))
        assertNull(listOf(interval(type = "outro", startTime = -30.0)).contentEndPercent(100_000L))
    }

    @Test
    fun `a marker that is not a number answers nothing`() {
        assertNull(
            listOf(interval(type = "outro", startTime = Double.NaN)).contentEndPercent(100_000L)
        )
        assertNull(
            listOf(interval(type = "outro", startTime = Double.POSITIVE_INFINITY))
                .contentEndPercent(100_000L)
        )
    }

    @Test
    fun `an unusable marker does not hide the usable one`() {
        val intervals = listOf(
            interval(type = "outro", startTime = 150.0),
            interval(type = "movie-credits", startTime = 88.0)
        )

        // The earliest marker is outside the video, but that does not make it a content end: a
        // threshold cannot be read from a value that is not a position inside this playback.
        assertEquals(88.0, intervals.contentEndPercent(100_000L)!!, 1e-9)
    }

    @Test
    fun `the tracker reads the marker five seconds early, whatever the runtime`() {
        // A 20 minute episode: the credits at 18:20 are read from 18:15.
        val episode = listOf(interval(type = "outro", startTime = 1_100.0))
            .contentEndPercent(1_200_000L)!!
            .contentEndWithTolerance(1_200_000L)!!
        assertEquals(1_095.0 / 1_200.0 * 100.0, episode, 1e-9)

        // A two hour film: the credits at 1:58:20 are read from 1:58:15. The same five seconds, not a
        // share of the runtime.
        val film = listOf(interval(type = "outro", startTime = 7_100.0))
            .contentEndPercent(7_200_000L)!!
            .contentEndWithTolerance(7_200_000L)!!
        assertEquals(7_095.0 / 7_200.0 * 100.0, film, 1e-9)
    }

    @Test
    fun `a margin that reaches past the start of the video reads as the start`() {
        assertEquals(
            0.0,
            listOf(interval(type = "outro", startTime = 3.0))
                .contentEndPercent(1_200_000L)!!
                .contentEndWithTolerance(1_200_000L)!!,
            1e-9
        )
    }

    @Test
    fun `a marker that was not read gets no margin either`() {
        val none: Double? = null
        assertNull(none.contentEndWithTolerance(1_200_000L))
        assertNull(
            listOf(interval(type = "outro", startTime = 1_100.0)).contentEndPercent(0L)
        )
    }

    private fun interval(
        type: String,
        startTime: Double,
        endTime: Double = startTime + 5.0
    ) = SkipInterval(
        startTime = startTime,
        endTime = endTime,
        type = type,
        provider = "introdb"
    )
}
