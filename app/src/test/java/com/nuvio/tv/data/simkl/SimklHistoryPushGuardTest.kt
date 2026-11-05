package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A show-level mark makes Simkl mark every episode of a series watched, so nothing but a film may
 * leave the app without episode coordinates.
 *
 * The rule itself lives on `TrackingHistoryItem`, where every provider that writes history reads it,
 * and the Simkl writer is one of them: this checks the marks a Simkl history write may carry.
 */
class SimklHistoryPushGuardTest {
    private fun mark(
        kind: TrackingMediaKind,
        season: Int? = null,
        number: Int? = null
    ) = TrackingHistoryItem(
        media = TrackingMediaReference(
            kind = kind,
            title = "Rick and Morty",
            year = 2013,
            episode = if (season == null && number == null) {
                null
            } else {
                TrackingEpisode(season = season, number = number ?: 1)
            }
        ),
        watchedAtEpochMs = 1_700_000_000_000
    )

    private fun pushable(items: List<TrackingHistoryItem>): List<TrackingHistoryItem> =
        items.filterNot(TrackingHistoryItem::isWholeSeriesMark)

    @Test
    fun `a mark without an episode never reaches Simkl for a series`() {
        assertTrue(mark(TrackingMediaKind.SHOW).isWholeSeriesMark)
        assertTrue(mark(TrackingMediaKind.ANIME).isWholeSeriesMark)
        assertTrue(pushable(listOf(mark(TrackingMediaKind.SHOW), mark(TrackingMediaKind.ANIME))).isEmpty())
    }

    @Test
    fun `episode marks always reach Simkl`() {
        assertFalse(mark(TrackingMediaKind.SHOW, season = 1, number = 5).isWholeSeriesMark)
        val pushed = pushable(listOf(mark(TrackingMediaKind.SHOW, season = 1, number = 5)))
        assertEquals(1, pushed.size)
        assertEquals(5, pushed.single().media.episode?.number)
    }

    @Test
    fun `films need no episode`() {
        assertFalse(mark(TrackingMediaKind.MOVIE).isWholeSeriesMark)
        assertEquals(1, pushable(listOf(mark(TrackingMediaKind.MOVIE))).size)
    }

    @Test
    fun `a whole-series mark is dropped while its episodes still travel`() {
        val pushed = pushable(
            listOf(
                mark(TrackingMediaKind.SHOW),
                mark(TrackingMediaKind.SHOW, season = 1, number = 1),
                mark(TrackingMediaKind.SHOW, season = 1, number = 2)
            )
        )
        assertEquals(2, pushed.size)
        assertEquals(listOf(1, 2), pushed.map { item -> item.media.episode?.number })
    }

    @Test
    fun `an empty batch stays empty`() {
        assertTrue(pushable(emptyList()).isEmpty())
    }
}
