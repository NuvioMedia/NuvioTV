package com.nuvio.tv.domain.model

import kotlin.random.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RandomEpisodePickerTest {
    @Test
    fun `unwatched excludes marked and completed episodes but keeps partial progress`() {
        val picker = picker(
            episodes = (1..4).map(::episode),
            watched = setOf(1 to 1),
            progress = mapOf(1 to 2 to progress(2, 100f), 1 to 3 to progress(3, 40f))
        )

        assertEquals(4, picker.count(true))
        assertEquals(2, picker.count(false))
        assertEquals(setOf(3, 4), (1..2).map { picker.pick(false)?.episode }.toSet())
        assertTrue(picker.isWatched(episode(1)))
        assertTrue(picker.isWatched(episode(2)))
        assertFalse(picker.isWatched(episode(3)))
    }

    @Test
    fun `watched episodes require explicit inclusion when caught up`() {
        val picker = picker(listOf(episode(1)), watched = setOf(1 to 1))

        assertEquals(0, picker.count(false))
        assertNull(picker.pick(false))
        assertEquals(episode(1), picker.pick(true))
    }

    @Test
    fun `completion follows the tracking source threshold`() {
        val picker = picker(
            episodes = listOf(episode(1), episode(2)),
            progress = mapOf(
                (1 to 1) to progress(1, 80f).copy(source = WatchProgress.SOURCE_SIMKL_PLAYBACK),
                (1 to 2) to progress(2, 80f)
            )
        )

        assertEquals(1, picker.count(false))
        assertEquals(episode(2), picker.pick(false))
    }

    @Test
    fun `every episode appears before repeats and cycle boundary avoids immediate repeat`() {
        val episodes = (1..20).map(::episode)
        val picker = picker(episodes)
        val firstCycle = episodes.map { picker.pick(true) }
        val secondCycle = episodes.map { picker.pick(true) }

        assertEquals(episodes.toSet(), firstCycle.toSet())
        assertEquals(episodes.toSet(), secondCycle.toSet())
        assertNotEquals(firstCycle.last(), secondCycle.first())
    }

    @Test
    fun `changing the watched filter keeps prior picks out while fresh options remain`() {
        val picker = picker((1..4).map(::episode), watched = setOf(1 to 1))
        val first = picker.pick(false)
        val remaining = (1..3).map { picker.pick(true) }

        assertFalse(first in remaining)
        assertEquals(4, (remaining + first).toSet().size)
    }

    @Test
    fun `refresh preserves picks made while the new metadata was loading`() {
        val original = picker((1..4).map(::episode))
        val first = original.pick(true)
        val updated = picker((1..4).map { episode(it).copy(title = "Updated $it") })
        val second = original.pick(true)

        updated.inheritHistoryFrom(original)

        val nextPicks = (1..2).map { updated.pick(true)?.id }.toSet()
        assertFalse(first?.id in nextPicks)
        assertFalse(second?.id in nextPicks)
        assertEquals(2, nextPicks.size)
    }

    @Test
    fun `refreshed selection resolves current metadata and respects watched filtering`() {
        val updatedEpisode = episode(1).copy(title = "Updated title")
        val updated = picker(listOf(updatedEpisode, episode(2)), watched = setOf(1 to 1))

        assertEquals(updatedEpisode, updated.find(updatedEpisode.id, includeWatched = true))
        assertNull(updated.find(updatedEpisode.id, includeWatched = false))
        assertNull(updated.find(episode(3).id, includeWatched = true))
    }

    @Test
    fun `selection spans seasons and follows existing release availability rules`() {
        val available = episode(1)
        val secondSeason = episode(1).copy(id = "show:2:1", season = 2)
        val picker = picker(
            listOf(
                available,
                secondSeason,
                episode(2).copy(available = false),
                episode(3).copy(released = "2999-01-01T00:00:00Z"),
                episode(4).copy(season = 0),
                episode(5).copy(season = null),
                episode(6).copy(episode = null),
                episode(0),
                episode(7).copy(id = " ")
            )
        )

        assertEquals(2, picker.count(true))
        assertEquals(setOf(available, secondSeason), (1..2).map { picker.pick(true) }.toSet())
    }

    @Test
    fun `duplicates do not bias the selection or produce repeated playback ids`() {
        val first = episode(1)
        val picker = picker(listOf(first, first.copy(id = "alternate"), first.copy(episode = 2), episode(3)))

        assertEquals(2, picker.count(true))
        assertEquals(setOf(first, episode(3)), (1..2).map { picker.pick(true) }.toSet())
    }

    @Test
    fun `unknown release dates remain eligible`() {
        val unknown = episode(1).copy(released = null)
        assertEquals(unknown, picker(listOf(unknown)).pick(false))
    }

    @Test
    fun `empty and single episode pools are safe`() {
        assertNull(picker(emptyList()).pick(true))
        val single = picker(listOf(episode(1)))
        repeat(3) { assertEquals(episode(1), single.pick(false)) }
    }

    private fun picker(
        episodes: List<Video>,
        watched: Set<Pair<Int, Int>> = emptySet(),
        progress: Map<Pair<Int, Int>, WatchProgress> = emptyMap()
    ) = RandomEpisodePicker(
        meta = Meta(
            id = "show", type = ContentType.SERIES, name = "Show", poster = null,
            posterShape = PosterShape.POSTER, background = null, logo = null, description = null,
            releaseInfo = null, imdbRating = null, genres = emptyList(), runtime = null,
            director = emptyList(), cast = emptyList(), videos = episodes, country = null,
            awards = null, language = null, links = emptyList()
        ),
        watchedEpisodes = watched,
        episodeProgress = progress,
        random = Random(42)
    )

    private fun episode(number: Int) = Video(
        id = "show:1:$number", title = "Episode $number", released = "2020-01-01",
        thumbnail = null, season = 1, episode = number, overview = null
    )

    private fun progress(number: Int, percent: Float) = WatchProgress(
        contentId = "show", contentType = "series", name = "Show", poster = null,
        backdrop = null, logo = null, videoId = "show:1:$number", season = 1,
        episode = number, episodeTitle = null, position = 0, duration = 0,
        lastWatched = 0, progressPercent = percent
    )
}
