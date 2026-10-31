package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A series reports the runtime of the show, not of the episode, so the percentage must not be scaled
 * by it. Only a film may carry a duration of its own, the player scales an episode row itself.
 */
class SimklEpisodeRuntimeTest {
    private fun media(runtime: Int?) = SimklMedia(
        title = "Rick and Morty",
        runtime = runtime,
        ids = mapOf("simkl" to JsonPrimitive(39687))
    )

    private fun episodeSession(progress: Double, runtime: Int?) = SimklPlaybackSession(
        id = 1,
        progress = progress,
        pausedAt = "2026-09-19T20:00:00Z",
        show = media(runtime),
        episode = SimklPlaybackEpisode(season = 1, number = 1, tvdbSeason = 1, tvdbNumber = 1)
    )

    @Test
    fun `an episode row carries no duration, so the player scales the percentage itself`() {
        val entry = requireNotNull(episodeSession(progress = 83.0, runtime = 52).toWatchProgress())
        assertEquals(0L, entry.duration)
        assertEquals(0L, entry.position)
    }

    @Test
    fun `a lower percentage on an episode row invents no timecode either`() {
        val entry = requireNotNull(episodeSession(progress = 40.0, runtime = 52).toWatchProgress())
        assertEquals(0L, entry.duration)
        assertEquals(0L, entry.position)
    }

    @Test
    fun `an episode row survives a show without a runtime`() {
        val entry = requireNotNull(episodeSession(progress = 50.0, runtime = null).toWatchProgress())
        assertEquals(0L, entry.duration)
        assertEquals(0L, entry.position)
    }

    @Test
    fun `a movie row keeps the runtime it really has`() {
        val session = SimklPlaybackSession(
            id = 2,
            progress = 50.0,
            pausedAt = "2026-09-19T20:00:00Z",
            movie = media(120)
        )
        val entry = requireNotNull(session.toWatchProgress())
        assertEquals(120 * 60_000L, entry.duration)
        assertEquals(60 * 60_000L, entry.position)
    }
}
