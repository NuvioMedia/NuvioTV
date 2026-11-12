package com.nuvio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class ContinueWatchingReleaseRefreshTest {
    private val now = Instant.parse("2026-11-12T12:00:00Z").toEpochMilli()
    private val hour = 60L * 60 * 1000

    private fun nextUp(released: String?, hasAired: Boolean, id: String = "tt1") = ContinueWatchingItem.NextUp(
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
            hasAired = hasAired,
            lastWatched = 1L,
            sortTimestamp = 1L
        )
    )

    @Test
    fun `the next refresh is when the soonest upcoming episode airs`() {
        val items = listOf(
            nextUp("2026-11-12T20:00:00Z", hasAired = false, id = "tt1"),
            nextUp("2026-11-12T15:00:00Z", hasAired = false, id = "tt2")
        )

        assertEquals(Instant.parse("2026-11-12T15:00:00Z").toEpochMilli(), nextContinueWatchingReleaseMs(items, emptyList(), now))
    }

    @Test
    fun `aired episodes and past releases do not schedule a refresh`() {
        val items = listOf(
            nextUp("2026-11-12T15:00:00Z", hasAired = true),
            nextUp("2026-11-12T10:00:00Z", hasAired = false, id = "tt2"),
            nextUp(null, hasAired = false, id = "tt3")
        )

        assertNull(nextContinueWatchingReleaseMs(items, emptyList(), now))
    }

    @Test
    fun `a series due to be looked up again schedules a refresh too`() {
        assertEquals(now + 2 * hour, nextContinueWatchingReleaseMs(emptyList(), listOf(now - hour, now + 2 * hour), now))
    }

    @Test
    fun `a release passed since the last cycle is noticed on return`() {
        val items = listOf(nextUp("2026-11-12T11:00:00Z", hasAired = false))

        assertTrue(hasContinueWatchingReleaseSince(items, emptyList(), sinceMs = now - 2 * hour, nowMs = now))
        assertFalse(hasContinueWatchingReleaseSince(items, emptyList(), sinceMs = now - 30 * 60_000L, nowMs = now))
    }
}
