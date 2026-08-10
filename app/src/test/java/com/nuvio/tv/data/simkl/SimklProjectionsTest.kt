package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklProjectionsTest {
    @Test
    fun `library presentation uses status names without provider prefix`() {
        assertEquals(
            listOf("Watching", "Plan to Watch", "On Hold", "Completed", "Dropped"),
            simklLibraryStatusDefinitions.map { it.title }
        )
    }

    @Test
    fun `library projection exposes populated statuses and attribution`() {
        val plan = entry(
            SimklMediaType.MOVIES,
            SimklListStatus.PLAN_TO_WATCH,
            53536,
            "tt0181852",
            slug = "terminator-3-rise-of-the-machines",
            addedAt = "2023-11-14T22:13:20Z"
        )
        val completed = entry(SimklMediaType.MOVIES, SimklListStatus.COMPLETED, 53434, "tt0068646")
        val watching = entry(SimklMediaType.SHOWS, SimklListStatus.WATCHING, 2090, "tt1520211")

        val projection = SimklSyncSnapshot(entries = listOf(plan, completed, watching))
            .toSimklLibraryProjection()
        val item = projection.items.single { it.id == "tt0181852" }

        assertEquals(3, projection.items.size)
        assertEquals("simkl", item.trackingProviderId)
        assertEquals("simkl:53536", item.trackingProviderItemId)
        assertEquals(
            "https://simkl.com/movies/53536/terminator-3-rise-of-the-machines",
            item.trackingSourceUrl
        )
        assertTrue(item.poster.orEmpty().contains("simkl.in/posters/12/poster_m.webp"))
        assertEquals(1_700_000_000_000L, item.listedAt)
        assertEquals(5, projection.tabs.size)
        assertFalse(projection.tabs.single { it.title == "Completed" }.isMembershipDestination)
    }

    @Test
    fun `watched projection includes movie exact episodes and completed series marker`() {
        val movie = entry(
            SimklMediaType.MOVIES,
            SimklListStatus.COMPLETED,
            53536,
            "tt0181852",
            lastWatchedAt = "2023-11-14T22:13:20Z"
        )
        val show = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.WATCHING,
            2090,
            "tt1520211",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(
                        SimklEpisode(1, "2023-11-14T23:13:20Z"),
                        SimklEpisode(2, null)
                    )
                )
            )
        )
        val completedAnime = entry(
            SimklMediaType.ANIME,
            SimklListStatus.COMPLETED,
            39687,
            "tt2560140",
            lastWatchedAt = "2023-11-15T00:13:20Z"
        )

        val projection = SimklSyncSnapshot(entries = listOf(movie, show, completedAnime))
            .toSimklWatchedProjection()

        assertEquals(3, projection.items.size)
        assertEquals("simkl", projection.items.single { it.contentType == "movie" }.trackingProviderId)
        assertTrue(projection.items.any { it.contentId == "tt1520211" && it.season == 1 && it.episode == 1 })
        assertFalse(projection.items.any { it.episode == 2 })
        assertTrue(projection.items.any { it.contentId == "tt2560140" && it.season == null })
        assertTrue(projection.fullyWatchedSeriesKeys.any { "tt2560140" in it })
    }

    @Test
    fun `summary counters never fabricate exact episode history`() {
        val summary = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.WATCHING,
            2090,
            "tt1520211",
            lastWatchedAt = "2023-11-14T23:13:20Z"
        ).copy(
            lastWatched = "S01E03",
            nextToWatch = "S01E04",
            watchedEpisodesCount = 3,
            totalEpisodesCount = 6
        )

        val projection = SimklSyncSnapshot(entries = listOf(summary)).toSimklWatchedProjection()

        assertTrue(projection.items.isEmpty())
        assertTrue(projection.fullyWatchedSeriesKeys.isEmpty())
    }

    @Test
    fun `anime watched projection prefers mapped tvdb coordinates`() {
        val anime = entry(
            SimklMediaType.ANIME,
            SimklListStatus.WATCHING,
            439744,
            "tt2560140",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(
                        SimklEpisode(
                            4,
                            "2023-11-14T23:13:20Z",
                            SimklEpisodeMapping(2, 4)
                        )
                    )
                )
            )
        )

        val watched = SimklSyncSnapshot(entries = listOf(anime)).toSimklWatchedProjection().items.single()

        assertEquals(2, watched.season)
        assertEquals(4, watched.episode)
    }

    @Test
    fun `next up projection applies furthest preference before collapsing episode history`() {
        val show = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.WATCHING,
            2090,
            "tt1520211",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(
                        SimklEpisode(3, "2023-11-15T00:13:20Z"),
                        SimklEpisode(8, "2023-11-14T23:13:20Z")
                    )
                )
            )
        )

        val furthest = SimklSyncSnapshot(entries = listOf(show))
            .toSimklNextUpSeeds(preferFurthestEpisode = true)
            .single()
        val recent = SimklSyncSnapshot(entries = listOf(show))
            .toSimklNextUpSeeds(preferFurthestEpisode = false)
            .single()

        assertEquals(8, furthest.episode)
        assertEquals(parseSimklUtcEpochMs("2023-11-14T23:13:20Z"), furthest.lastWatched)
        assertEquals(3, recent.episode)
        assertEquals(parseSimklUtcEpochMs("2023-11-15T00:13:20Z"), recent.lastWatched)
    }

    @Test
    fun `next up projection resolves equal timestamps by furthest episode`() {
        val show = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.WATCHING,
            2090,
            "tt1520211",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(
                        SimklEpisode(1, "2023-11-15T00:13:20Z"),
                        SimklEpisode(8, "2023-11-15T00:13:20Z")
                    )
                )
            )
        )

        val seed = SimklSyncSnapshot(entries = listOf(show))
            .toSimklNextUpSeeds(preferFurthestEpisode = true)
            .single()

        assertEquals(8, seed.episode)
    }

    @Test
    fun `next up projection excludes dropped shows`() {
        val dropped = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.DROPPED,
            2090,
            "tt1520211",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(SimklEpisode(3, "2023-11-15T00:13:20Z"))
                )
            )
        )

        val seeds = SimklSyncSnapshot(entries = listOf(dropped))
            .toSimklNextUpSeeds(preferFurthestEpisode = true)

        assertTrue(seeds.isEmpty())
    }

    @Test
    fun `next up projection excludes on hold shows`() {
        val onHold = entry(
            SimklMediaType.SHOWS,
            SimklListStatus.ON_HOLD,
            2090,
            "tt1520211",
            seasons = listOf(
                SimklSeason(
                    1,
                    listOf(SimklEpisode(3, "2023-11-15T00:13:20Z"))
                )
            )
        )

        val seeds = SimklSyncSnapshot(entries = listOf(onHold))
            .toSimklNextUpSeeds(preferFurthestEpisode = true)

        assertTrue(seeds.isEmpty())
    }

    @Test
    fun `playback projection preserves session identity percentage and eighty percent completion`() {
        val session = SimklPlaybackSession(
            id = 12345,
            progress = 80.0,
            pausedAt = "2024-04-30T22:13:00.250Z",
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 3, title = "Chapter Three"),
            show = media(39687, "tt4574334", runtime = 50)
        )

        val progress = SimklSyncSnapshot(playback = listOf(session)).toSimklProgressEntries().single()

        assertEquals("tt4574334", progress.contentId)
        assertEquals(1, progress.season)
        assertEquals(3, progress.episode)
        assertEquals(80.0f, progress.progressPercent)
        assertEquals(3_000_000L, progress.duration)
        assertEquals(2_400_000L, progress.position)
        assertEquals(12345L, progress.simklPlaybackId)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, progress.source)
        assertEquals("simkl:39687", progress.trackingProviderItemId)
        assertTrue(progress.isCompleted())
        assertFalse(progress.isInProgress())
        assertEquals(1_714_515_180_250L, progress.lastWatched)
    }

    @Test
    fun `media reference retains anime kind and accepted ids`() {
        val anime = entry(SimklMediaType.ANIME, SimklListStatus.WATCHING, 39687, "tt2560140", 16498)
        val reference = SimklSyncSnapshot(entries = listOf(anime)).mediaReference(
            "tt2560140",
            "series",
            season = 2,
            episode = 4,
            posterUrl = "https://catalog.example/anime.webp"
        )

        assertEquals(TrackingMediaKind.ANIME, reference.kind)
        assertEquals(39687L, reference.ids.simkl)
        assertEquals(16498L, reference.ids.mal)
        assertEquals(2, reference.episode?.season)
        assertEquals(4, reference.episode?.number)
        assertEquals("https://catalog.example/anime.webp", reference.posterUrl)
    }

    @Test
    fun `timestamp parser accepts UTC fractions and rejects invalid calendar values`() {
        assertEquals(0L, parseSimklUtcEpochMs("1970-01-01T00:00:00Z"))
        assertEquals(951_782_400_123L, parseSimklUtcEpochMs("2000-02-29T00:00:00.123Z"))
        assertNull(parseSimklUtcEpochMs("2023-02-29T00:00:00Z"))
        assertNull(parseSimklUtcEpochMs("2024-01-01T00:00:00+01:00"))
    }

    private fun entry(
        type: SimklMediaType,
        status: SimklListStatus,
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        slug: String? = null,
        addedAt: String? = null,
        lastWatchedAt: String? = null,
        seasons: List<SimklSeason> = emptyList()
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = type,
        status = status,
        addedToWatchlistAt = addedAt,
        lastWatchedAt = lastWatchedAt,
        seasons = seasons,
        movie = if (type == SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null,
        show = if (type != SimklMediaType.MOVIES) media(id, imdb, mal, slug = slug) else null
    )

    private fun media(
        id: Long,
        imdb: String? = null,
        mal: Long? = null,
        runtime: Int? = null,
        slug: String? = null
    ): SimklMedia = SimklMedia(
        title = "Title $id",
        poster = "12/poster",
        year = 2020,
        runtime = runtime,
        ids = buildJsonObject {
            put("simkl", id)
            imdb?.let { put("imdb", it) }
            mal?.let { put("mal", it) }
            slug?.let { put("slug", it) }
        }
    )
}
