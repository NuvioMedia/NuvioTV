package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.AnimeSkipEpisode
import com.nuvio.tv.data.remote.api.AnimeSkipTimestamp
import com.nuvio.tv.data.remote.api.AnimeSkipTimestampType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AnimeSkipIntervalsTest {

    private fun ts(at: Double, name: String) = AnimeSkipTimestamp(at, AnimeSkipTimestampType(name))

    @Test
    fun `lone intro marker without end is dropped`() {
        assertTrue(listOf(ts(22.0, "Intro")).toAnimeSkipIntervals().isEmpty())
    }

    @Test
    fun `lone recap marker without end is dropped`() {
        assertTrue(listOf(ts(0.0, "Recap")).toAnimeSkipIntervals(baseDuration = 1430.0).isEmpty())
    }

    @Test
    fun `trailing credits marker runs to the end of the episode`() {
        val result = listOf(
            ts(0.0, "Intro"),
            ts(90.0, "Canon"),
            ts(1300.0, "Credits")
        ).toAnimeSkipIntervals(baseDuration = 1430.0)

        assertEquals(
            listOf(
                SkipInterval(0.0, 90.0, "op", "animeskip"),
                SkipInterval(1300.0, Double.MAX_VALUE, "ed", "animeskip")
            ),
            result
        )
    }

    @Test
    fun `trailing credits marker in the first half of the episode is dropped`() {
        assertTrue(listOf(ts(22.0, "Credits")).toAnimeSkipIntervals(baseDuration = 1430.0).isEmpty())
    }

    @Test
    fun `trailing mixed credits marker runs to the end of the episode`() {
        assertEquals(
            listOf(SkipInterval(1300.0, Double.MAX_VALUE, "mixed-ed", "animeskip")),
            listOf(ts(1300.0, "Mixed Credits")).toAnimeSkipIntervals(baseDuration = 1430.0)
        )
    }

    @Test
    fun `trailing credits marker is kept when the duration is unknown`() {
        assertEquals(
            listOf(SkipInterval(1300.0, Double.MAX_VALUE, "ed", "animeskip")),
            listOf(ts(1300.0, "Credits")).toAnimeSkipIntervals()
        )
    }

    @Test
    fun `segments end where the next marker starts, regardless of input order`() {
        val result = listOf(
            ts(1300.0, "Credits"),
            ts(90.0, "Canon"),
            ts(1390.0, "Preview"),
            ts(0.0, "Recap"),
            ts(30.0, "New Intro")
        ).toAnimeSkipIntervals()

        assertEquals(
            listOf(
                SkipInterval(0.0, 30.0, "recap", "animeskip"),
                SkipInterval(30.0, 90.0, "op", "animeskip"),
                SkipInterval(1300.0, 1390.0, "ed", "animeskip")
            ),
            result
        )
    }

    @Test
    fun `zero length segment from duplicate timestamps is dropped`() {
        val result = listOf(
            ts(10.0, "Intro"),
            ts(10.0, "Canon"),
            ts(100.0, "Credits"),
            ts(200.0, "Canon")
        ).toAnimeSkipIntervals()

        assertEquals(listOf(SkipInterval(100.0, 200.0, "ed", "animeskip")), result)
    }

    private fun ep(vararg timestamps: AnimeSkipTimestamp) =
        AnimeSkipEpisode(number = "1042", baseDuration = 1430.0, timestamps = timestamps.toList())

    @Test
    fun `entry covering the most segment categories wins`() {
        val result = listOf(
            ep(ts(974.0, "Intro")),
            ep(ts(13.0, "Intro"), ts(130.0, "Canon")),
            ep(ts(0.0, "Intro"), ts(130.0, "Recap"), ts(300.0, "Canon"), ts(1300.0, "Credits"), ts(1395.0, "Preview"))
        ).bestAnimeSkipIntervals()

        assertEquals(listOf("op", "recap", "ed"), result.map { it.type })
    }

    @Test
    fun `more segments break a category tie and earlier entry wins a full tie`() {
        val twoOpenings = ep(ts(0.0, "Intro"), ts(30.0, "Canon"), ts(600.0, "Mixed Intro"), ts(650.0, "Canon"))
        val oneOpening = ep(ts(10.0, "Intro"), ts(100.0, "Canon"))
        val otherOneOpening = ep(ts(20.0, "Intro"), ts(110.0, "Canon"))

        assertEquals(2, listOf(oneOpening, twoOpenings).bestAnimeSkipIntervals().size)
        assertEquals(10.0, listOf(oneOpening, otherOneOpening).bestAnimeSkipIntervals().single().startTime, 0.0)
    }

    @Test
    fun `no usable entry gives no segments`() {
        assertTrue(listOf(ep(ts(22.0, "Intro")), ep()).bestAnimeSkipIntervals().isEmpty())
    }
}
