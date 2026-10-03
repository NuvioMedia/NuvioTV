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
    fun `playback projection preserves session identity and stays a position to resume`() {
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
        // No duration is invented from the show runtime, so the resume goes through the percentage:
        // the player scales it by the duration of the episode it really opened.
        assertEquals(0L, progress.duration)
        assertEquals(0L, progress.position)
        assertEquals(12345L, progress.simklPlaybackId)
        assertEquals(WatchProgress.SOURCE_SIMKL_PLAYBACK, progress.source)
        assertEquals("simkl:39687", progress.trackingProviderItemId)
        // Where this playback ends is the credits marker of the release, and a row Simkl publishes
        // does not carry it, so even its eighty percent is only a position to resume.
        assertTrue(progress.isProviderPlaybackPosition)
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
        assertEquals(1_714_515_180_250L, progress.lastWatched)
    }

    @Test
    fun `a playback row at ninety percent is not completed by a threshold of eighty five`() {
        val session = SimklPlaybackSession(
            id = 12349,
            progress = 90.0,
            pausedAt = "2024-04-30T22:13:00Z",
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 3, title = "Chapter Three"),
            show = media(39687, "tt4574334", runtime = 50)
        )

        val progress = SimklSyncSnapshot(playback = listOf(session))
            .toSimklProgressEntries(completionThresholdFraction = 0.85f)
            .single()

        // The stop was reported as a pause, because 90 percent sat under the credits marker of 97, so
        // the account holds this row open. Reading the row against the user threshold of 85 alone
        // would call it finished and drop the episode out of Continue Watching. The row is exempt, so
        // it stays a position: isCompleted() is false and isInProgress() is true.
        assertEquals(0.85f, progress.completionThresholdFraction)
        assertTrue(progress.isProviderPlaybackPosition)
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
    }

    @Test
    fun `a playback row read with the stored threshold stays in progress under it`() {
        val session = SimklPlaybackSession(
            id = 12346,
            progress = 86.0,
            pausedAt = "2024-04-30T22:13:00Z",
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 3, title = "Chapter Three"),
            show = media(39687, "tt4574334", runtime = 50)
        )

        val progress = SimklSyncSnapshot(playback = listOf(session))
            .toSimklProgressEntries(completionThresholdFraction = 0.95f)
            .single()

        // The stop that wrote this row was reported as a pause, because it sat under the threshold of
        // the user, and the row keeps the number it was reported with. shouldTreatAsInProgressFor
        // ContinueWatching drops a row the moment isCompleted() is true, and a playback row never is,
        // so an episode stopped at 86 percent of a threshold of 95 stays on the row.
        assertEquals(0.95f, progress.completionThresholdFraction)
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
    }

    @Test
    fun `a playback row at the stored threshold is still only a position to resume`() {
        val session = SimklPlaybackSession(
            id = 12347,
            progress = 96.0,
            pausedAt = "2024-04-30T22:13:00Z",
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 3, title = "Chapter Three"),
            show = media(39687, "tt4574334", runtime = 50)
        )

        val progress = SimklSyncSnapshot(playback = listOf(session))
            .toSimklProgressEntries(completionThresholdFraction = 0.96f)
            .single()

        // The stored threshold is the credits marker the write side resolved, so a row at that
        // percentage was reported as a pause and stays a position, whatever the number says.
        assertEquals(0.96f, progress.completionThresholdFraction)
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
    }

    @Test
    fun `a playback row without a stored threshold keeps the eighty percent of its source`() {
        val session = SimklPlaybackSession(
            id = 12348,
            progress = 86.0,
            pausedAt = "2024-04-30T22:13:00Z",
            type = "episode",
            episode = SimklPlaybackEpisode(season = 1, number = 3, title = "Chapter Three"),
            show = media(39687, "tt4574334", runtime = 50)
        )

        // Nothing was read, so the row carries no fraction of its own.
        val progress = SimklSyncSnapshot(playback = listOf(session))
            .toSimklProgressEntries()
            .single()

        assertNull(progress.completionThresholdFraction)
        // The eighty percent of its source is a reading threshold, not a finish, so the row is only
        // the position Simkl kept open.
        assertFalse(progress.isCompleted())
        assertTrue(progress.isInProgress())
    }

    @Test
    fun `the stored threshold becomes the fraction the playback row is read with`() {
        assertEquals(0.95f, resolvedSimklCompletionFraction(95))
        assertEquals(0.80f, resolvedSimklCompletionFraction(80))
        // Under the bar Simkl itself needs a watch cannot be read, whatever the setting holds.
        assertEquals(0.80f, resolvedSimklCompletionFraction(50))
        assertNull(resolvedSimklCompletionFraction(null))
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
