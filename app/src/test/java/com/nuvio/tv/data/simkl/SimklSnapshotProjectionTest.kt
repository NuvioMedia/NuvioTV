package com.nuvio.tv.data.simkl

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklSnapshotProjectionTest {
    @Test
    fun `projection preserves existing library watched and playback results`() {
        val show = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            media = media(1, "tt0000001", tmdb = 101),
            episodes = listOf(
                SimklEpisode(1, WATCHED_AT),
                SimklEpisode(2, WATCHED_AT)
            )
        )
        val movie = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            media = media(2, "tt0000002"),
            lastWatchedAt = WATCHED_AT
        )
        val playback = SimklPlaybackSession(
            id = 9,
            progress = 25.0,
            pausedAt = WATCHED_AT,
            episode = SimklPlaybackEpisode(season = 1, number = 2),
            show = show.media
        )
        val snapshot = SimklSyncSnapshot(
            entries = listOf(show, movie),
            playback = listOf(playback)
        )

        val projection = SimklSnapshotProjection.create(snapshot)

        assertEquals(snapshot.toSimklLibraryProjection(), projection.library)
        assertEquals(snapshot.toSimklWatchedProjection(), projection.watched)
        assertEquals(snapshot.toSimklProgressEntries(), projection.progress)
        assertEquals(2, projection.nextUp(true).single().episode)
        assertSame(projection.nextUp(true), projection.nextUp(true))
    }

    @Test
    fun `indexed lookups preserve aliases partial progress and membership`() {
        val show = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.WATCHING,
            media = media(1, "tt0000001", tmdb = 101),
            episodes = listOf(
                SimklEpisode(1, WATCHED_AT),
                SimklEpisode(2, WATCHED_AT)
            )
        )
        val snapshot = SimklSyncSnapshot(
            entries = listOf(show),
            playback = listOf(
                SimklPlaybackSession(
                    id = 9,
                    progress = 25.0,
                    pausedAt = WATCHED_AT,
                    episode = SimklPlaybackEpisode(season = 1, number = 2),
                    show = show.media
                )
            )
        )

        val projection = SimklSnapshotProjection.create(snapshot)
        val progress = projection.episodeProgress("tmdb:101")

        assertEquals(2, progress.size)
        assertTrue(progress.getValue(1 to 1).isCompleted())
        assertEquals(0.25f, progress.getValue(1 to 2).progressPercentage)
        assertTrue(projection.isWatched("tmdb:101", null, 1, 1))
        assertFalse(projection.isWatched("tt0000001", null, 1, 2))
        assertEquals("simkl:status:watching", projection.membershipKey("tmdb:101", "series"))
        assertTrue("tmdb:101" in projection.showIdSiblings.getValue("tt0000001"))
    }

    @Test
    fun `indexed lookups preserve hidden anime and movie rules`() {
        val dropped = entry(
            type = SimklMediaType.SHOWS,
            status = SimklListStatus.DROPPED,
            media = media(1, "tt0000001", tmdb = 101),
            episodes = listOf(SimklEpisode(1, WATCHED_AT))
        )
        val anime = entry(
            type = SimklMediaType.ANIME,
            status = SimklListStatus.WATCHING,
            media = media(2, "tt0000002", mal = 202),
            episodes = listOf(SimklEpisode(7, WATCHED_AT))
        )
        val movieMedia = media(3, "tt0000003")
        val movie = entry(
            type = SimklMediaType.MOVIES,
            status = SimklListStatus.COMPLETED,
            media = movieMedia,
            lastWatchedAt = WATCHED_AT
        )
        val snapshot = SimklSyncSnapshot(
            entries = listOf(dropped, anime, movie),
            playback = listOf(
                SimklPlaybackSession(
                    id = 10,
                    progress = 10.0,
                    pausedAt = WATCHED_AT,
                    movie = movieMedia
                )
            )
        )

        val projection = SimklSnapshotProjection.create(snapshot)

        assertTrue(projection.isHidden("tmdb:101"))
        assertTrue(projection.nextUp(true).none { it.contentId == "tt0000001" })
        assertTrue(projection.isWatchedByVideoId("mal:202:7", 1))
        assertFalse(projection.isWatchedByVideoId("mal:202:8", 8))
        assertFalse("tt0000003" in projection.watchedMovieIds)
    }

    @Test(timeout = 20_000)
    fun `large account is projected once and queried through indexes`() {
        val seriesCount = 386
        val series = (1..seriesCount).map { index ->
            val episodeCount = if (index <= 251) 44 else 43
            entry(
                type = SimklMediaType.SHOWS,
                status = SimklListStatus.WATCHING,
                media = media(index.toLong(), imdbId(index)),
                episodes = (1..episodeCount).map { episode -> SimklEpisode(episode, WATCHED_AT) }
            )
        }
        val movies = (1..623).map { index ->
            val id = 10_000 + index
            entry(
                type = SimklMediaType.MOVIES,
                status = SimklListStatus.PLAN_TO_WATCH,
                media = media(id.toLong(), imdbId(id))
            )
        }

        val projection = SimklSnapshotProjection.create(
            SimklSyncSnapshot(entries = series + movies)
        )

        assertEquals(1_009, projection.library.items.size)
        assertEquals(16_849, projection.watched.items.size)
        assertEquals(386, projection.nextUp(true).size)
        repeat(10_000) { index ->
            assertTrue(projection.isWatched(imdbId(index % seriesCount + 1), null, 1, 1))
        }
    }

    private fun entry(
        type: SimklMediaType,
        status: SimklListStatus,
        media: SimklMedia,
        episodes: List<SimklEpisode> = emptyList(),
        lastWatchedAt: String? = null
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = type,
        status = status,
        lastWatchedAt = lastWatchedAt,
        show = media.takeUnless { type == SimklMediaType.MOVIES },
        movie = media.takeIf { type == SimklMediaType.MOVIES },
        seasons = episodes.takeIf { values -> values.isNotEmpty() }
            ?.let { listOf(SimklSeason(1, it)) }
            .orEmpty()
    )

    private fun media(
        simkl: Long,
        imdb: String,
        tmdb: Long? = null,
        mal: Long? = null
    ): SimklMedia = SimklMedia(
        title = "Title $simkl",
        ids = buildJsonObject {
            put("simkl", simkl)
            put("imdb", imdb)
            tmdb?.let { put("tmdb", it) }
            mal?.let { put("mal", it) }
        }
    )

    private fun imdbId(id: Int): String = "tt${id.toString().padStart(7, '0')}"

    private companion object {
        const val WATCHED_AT = "2024-01-01T00:00:00Z"
    }
}
