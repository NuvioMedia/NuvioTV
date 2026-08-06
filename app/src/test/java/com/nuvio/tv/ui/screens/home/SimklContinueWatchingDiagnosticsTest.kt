package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SimklContinueWatchingDiagnosticsTest {
    @Test
    fun `fresh resolver result that is already watched is identified`() {
        val card = nextUpCard(episode = 3)

        val record = report(
            card = card,
            watched = listOf(watched(1), watched(2), watched(3)),
            seed = seed(2),
            fresh = listOf(card)
        ).records.single()

        assertEquals(
            SimklCwDiagnosticFinding.RESOLVER_RETURNED_WATCHED_EPISODE,
            record.finding
        )
        assertEquals(SimklCwDiagnosticOrigin.FRESH, record.origin)
        assertTrue(record.displayedInExactHistory)
    }

    @Test
    fun `cached result that is already watched is identified`() {
        val card = nextUpCard(episode = 3)

        val record = report(
            card = card,
            watched = listOf(watched(1), watched(2), watched(3)),
            seed = seed(3),
            cached = listOf(card)
        ).records.single()

        assertEquals(
            SimklCwDiagnosticFinding.WATCHED_EPISODE_FROM_CACHE,
            record.finding
        )
        assertEquals(SimklCwDiagnosticOrigin.CACHE, record.origin)
    }

    @Test
    fun `card without a current seed is identified`() {
        val card = nextUpCard(episode = 3)

        val record = report(
            card = card,
            watched = listOf(watched(1), watched(2)),
            seed = null,
            cached = listOf(card)
        ).records.single()

        assertEquals(SimklCwDiagnosticFinding.CARD_WITHOUT_ACTIVE_SEED, record.finding)
    }

    @Test
    fun `furthest mode identifies a seed behind exact history`() {
        val card = nextUpCard(episode = 3)

        val record = report(
            card = card,
            watched = listOf(watched(1), watched(4)),
            seed = seed(1),
            fresh = listOf(card)
        ).records.single()

        assertEquals(SimklCwDiagnosticFinding.SEED_BEHIND_FURTHEST, record.finding)
        assertEquals(SimklCwDiagnosticEpisode(1, 4), record.furthest)
    }

    @Test
    fun `unwatched episode after the active seed is consistent`() {
        val card = nextUpCard(episode = 3)

        val record = report(
            card = card,
            watched = listOf(watched(1), watched(2)),
            seed = seed(2),
            fresh = listOf(card)
        ).records.single()

        assertEquals(SimklCwDiagnosticFinding.CONSISTENT_NEXT_UP, record.finding)
        assertFalse(record.displayedInExactHistory)
    }

    @Test
    fun `older watched event suppressible playback is identified`() {
        val progress = progress(episode = 2, lastWatched = 100L)

        val record = buildSimklCwDisplayDiagnosticReport(
            displayedItems = listOf(ContinueWatchingItem.InProgress(progress)),
            liveProgress = listOf(progress),
            nextUpSeeds = emptyList(),
            watchedItems = listOf(watched(2, watchedAt = 200L)),
            freshNextUpItems = emptyList(),
            olderResolvedNextUpItems = emptyList(),
            cachedNextUpItems = emptyList(),
            preferFurthestEpisode = true,
            hasLoadedRemoteProgress = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertEquals(
            SimklCwDiagnosticFinding.STALE_PLAYBACK_NOT_RECONCILED,
            record.finding
        )
    }

    @Test
    fun `newer playback after watched event is identified as rewatch`() {
        val progress = progress(episode = 2, lastWatched = 300L)

        val record = buildSimklCwDisplayDiagnosticReport(
            displayedItems = listOf(ContinueWatchingItem.InProgress(progress)),
            liveProgress = listOf(progress),
            nextUpSeeds = emptyList(),
            watchedItems = listOf(watched(2, watchedAt = 200L)),
            freshNextUpItems = emptyList(),
            olderResolvedNextUpItems = emptyList(),
            cachedNextUpItems = emptyList(),
            preferFurthestEpisode = true,
            hasLoadedRemoteProgress = true,
            aliasFor = { "cw-test" }
        ).records.single()

        assertEquals(SimklCwDiagnosticFinding.NEWER_PLAYBACK_REWATCH, record.finding)
    }

    @Test
    fun `formatted display diagnostics omit media identity title and timestamps`() {
        val card = nextUpCard(episode = 3)
        val record = report(
            card = card,
            watched = listOf(watched(1), watched(2)),
            seed = seed(2),
            fresh = listOf(card)
        ).records.single()

        val line = record.logLine()

        assertTrue("key=cw-test" in line)
        assertFalse("tt-private-id" in line)
        assertFalse("Private Imported Show" in line)
        assertFalse("2026-07-24" in line)
    }

    private fun report(
        card: ContinueWatchingItem.NextUp,
        watched: List<WatchedItem>,
        seed: WatchProgress?,
        fresh: List<ContinueWatchingItem.NextUp> = emptyList(),
        cached: List<ContinueWatchingItem.NextUp> = emptyList()
    ) = buildSimklCwDisplayDiagnosticReport(
        displayedItems = listOf(card),
        liveProgress = emptyList(),
        nextUpSeeds = listOfNotNull(seed),
        watchedItems = watched,
        freshNextUpItems = fresh,
        olderResolvedNextUpItems = emptyList(),
        cachedNextUpItems = cached,
        preferFurthestEpisode = true,
        hasLoadedRemoteProgress = true,
        aliasFor = { "cw-test" }
    )

    private fun nextUpCard(episode: Int) = ContinueWatchingItem.NextUp(
        NextUpInfo(
            contentId = "tt-private-id",
            contentType = "series",
            name = "Private Imported Show",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "private-video",
            season = 1,
            episode = episode,
            episodeTitle = null,
            thumbnail = null,
            lastWatched = 300L,
            sortTimestamp = 300L,
            seedSeason = 1,
            seedEpisode = episode - 1
        )
    )

    private fun seed(episode: Int) = WatchProgress(
        contentId = "tt-private-id",
        contentType = "series",
        name = "Private Imported Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "private-seed",
        season = 1,
        episode = episode,
        episodeTitle = null,
        position = 1L,
        duration = 1L,
        lastWatched = episode * 100L,
        progressPercent = 100f,
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
        trackingProviderId = TrackingProviderId.SIMKL.storageId
    )

    private fun progress(episode: Int, lastWatched: Long) = WatchProgress(
        contentId = "tt-private-id",
        contentType = "series",
        name = "Private Imported Show",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "private-progress",
        season = 1,
        episode = episode,
        episodeTitle = null,
        position = 40L,
        duration = 100L,
        lastWatched = lastWatched,
        progressPercent = 40f,
        source = WatchProgress.SOURCE_SIMKL_PLAYBACK,
        trackingProviderId = TrackingProviderId.SIMKL.storageId
    )

    private fun watched(episode: Int, watchedAt: Long = episode * 100L) = WatchedItem(
        contentId = "tt-private-id",
        contentType = "series",
        title = "Private Imported Show",
        season = 1,
        episode = episode,
        watchedAt = watchedAt,
        trackingProviderId = TrackingProviderId.SIMKL.storageId
    )
}
