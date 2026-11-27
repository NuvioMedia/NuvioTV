package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.ContinueWatchingSortMode
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.util.TimeZone

@RunWith(Parameterized::class)
class ContinueWatchingUpcomingSortTest(private val mode: ContinueWatchingSortMode) {
    private val originalTimeZone = TimeZone.getDefault()

    @Before
    fun setTimeZone() {
        TimeZone.setDefault(TimeZone.getTimeZone("GMT-08:00"))
    }

    @After
    fun restoreTimeZone() {
        TimeZone.setDefault(originalTimeZone)
    }

    @Test
    fun `same day upcoming episodes air in time order`() {
        val evening = nextUp("evening", "2026-11-13T07:00:00Z")
        val morning = nextUp("morning", "2026-11-12T23:00:00+06:00")

        assertEquals(listOf("morning", "evening"), sortedIds(listOf(evening, morning)))
    }

    @Test
    fun `known release times precede date only releases on the same day`() {
        val dateOnly = nextUp("date-only", "2026-11-12")
        val embeddedDate = nextUp("embedded-date", "Released 2026-11-12")
        val evening = nextUp("local-morning", "2026-11-12T10:00:00")
        val morning = nextUp("morning", "2026-11-12T17:00:00Z")

        assertEquals(
            listOf("morning", "local-morning", "date-only", "embedded-date"),
            sortedIds(listOf(dateOnly, evening, embeddedDate, morning))
        )
    }

    @Test
    fun `local calendar days sort first with unknown dates last`() {
        val previousEvening = nextUp("previous-evening", "2026-11-12T06:00:00Z")
        val nextDay = nextUp("next-day", "2026-11-12")
        val missing = nextUp("missing", null)
        val invalid = nextUp("invalid", "not-a-date")

        assertEquals(
            listOf("previous-evening", "next-day", "missing", "invalid"),
            sortedIds(listOf(missing, nextDay, invalid, previousEvening))
        )
    }

    private fun sortedIds(items: List<ContinueWatchingItem.NextUp>): List<String> {
        val sorted = if (mode == ContinueWatchingSortMode.SPLIT_UPCOMING) {
            splitUpcomingItems(items, mode).second
        } else {
            sortContinueWatchingItems(items, mode)
        }
        return sorted.map { (it as ContinueWatchingItem.NextUp).info.contentId }
    }

    private fun nextUp(id: String, released: String?) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = id,
            contentType = "series",
            name = "Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "$id:1:2",
            season = 1,
            episode = 2,
            episodeTitle = null,
            thumbnail = null,
            released = released,
            hasAired = false,
            lastWatched = 1L,
            sortTimestamp = 1L
        )
    )

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun modes() = listOf(ContinueWatchingSortMode.SPLIT_UPCOMING, ContinueWatchingSortMode.STREAMING_STYLE)
    }
}
