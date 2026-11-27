package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Simkl publishes a new position on `/sync/playback` a moment after it answers a pause, so a read
 * that follows our own write can still describe the previous viewing. Our newer row has to win,
 * everything else stays with the account.
 */
class SimklPlaybackMergeTest {
    private fun session(
        id: Long,
        progress: Double,
        pausedAt: String,
        number: Int = 1
    ) = SimklPlaybackSession(
        id = id,
        progress = progress,
        pausedAt = pausedAt,
        show = SimklMedia(
            title = "Rick and Morty",
            runtime = 22,
            ids = mapOf("simkl" to JsonPrimitive(39687))
        ),
        episode = SimklPlaybackEpisode(season = 1, number = number, tvdbSeason = 1, tvdbNumber = number)
    )

    @Test
    fun `our newer pause survives a fetch that still shows the older one`() {
        val mine = session(id = 1, progress = 83.0, pausedAt = "2026-09-19T20:00:00Z")
        val fetched = session(id = 1, progress = 61.0, pausedAt = "2026-09-19T19:00:00Z")
        val merged = mergeFetchedPlayback(fetched = listOf(fetched), held = listOf(mine))
        assertEquals(1, merged.size)
        assertEquals(83.0, merged.single().progress, 0.001)
    }

    @Test
    fun `an account row that is newer than ours wins`() {
        val mine = session(id = 1, progress = 40.0, pausedAt = "2026-09-19T19:00:00Z")
        val fetched = session(id = 1, progress = 61.0, pausedAt = "2026-09-19T20:00:00Z")
        val merged = mergeFetchedPlayback(fetched = listOf(fetched), held = listOf(mine))
        assertEquals(61.0, merged.single().progress, 0.001)
    }

    @Test
    fun `a playback the account dropped stays dropped`() {
        val mine = session(id = 1, progress = 83.0, pausedAt = "2026-09-19T20:00:00Z")
        val merged = mergeFetchedPlayback(fetched = emptyList(), held = listOf(mine))
        assertEquals(0, merged.size)
    }

    @Test
    fun `rows of different episodes are not mixed up`() {
        val mine = session(id = 1, progress = 83.0, pausedAt = "2026-09-19T20:00:00Z", number = 2)
        val fetched = session(id = 1, progress = 61.0, pausedAt = "2026-09-19T19:00:00Z", number = 1)
        val merged = mergeFetchedPlayback(fetched = listOf(fetched), held = listOf(mine))
        assertEquals(1, merged.size)
        assertEquals(61.0, merged.single().progress, 0.001)
    }

    @Test
    fun `an empty fetched list leaves nothing behind`() {
        val merged = mergeFetchedPlayback(fetched = emptyList(), held = emptyList())
        assertEquals(0, merged.size)
    }

    @Test
    fun `a fetched row we never held is kept untouched`() {
        val fetched = session(id = 7, progress = 30.0, pausedAt = "2026-09-19T18:00:00Z")
        val merged = mergeFetchedPlayback(fetched = listOf(fetched), held = emptyList())
        assertEquals(1, merged.size)
        assertEquals(30.0, merged.single().progress, 0.001)
    }
}
