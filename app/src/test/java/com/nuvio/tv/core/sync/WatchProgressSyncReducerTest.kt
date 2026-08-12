package com.nuvio.tv.core.sync

import com.nuvio.tv.core.sync.WatchProgressSyncReducer.MergeDecision
import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchProgressSyncReducerTest {

    @Test
    fun `zero position and duration is not usable resume data`() {
        val empty = progress(position = 0L, duration = 0L, lastWatched = 50L)

        assertFalse(WatchProgressSyncReducer.hasUsableResumeData(empty))
        assertFalse(WatchProgressSyncReducer.shouldPushToRemote(empty))
    }

    @Test
    fun `paused save with position and unknown duration is still pushed`() {
        val paused = progress(position = 600_000L, duration = 0L, lastWatched = 50L)

        assertTrue(WatchProgressSyncReducer.hasUsableResumeData(paused))
        assertTrue(WatchProgressSyncReducer.shouldPushToRemote(paused))
    }

    @Test
    fun `dummy completed sentinel is not pushed`() {
        val sentinel = progress(position = 1L, duration = 1L, lastWatched = 50L)

        assertFalse(WatchProgressSyncReducer.shouldPushToRemote(sentinel))
    }

    @Test
    fun `canonicalize drops series mirror when episode row is at least as fresh`() {
        val episode = episodeProgress(
            contentId = "tt111",
            season = 1,
            episode = 3,
            position = 400_000L,
            lastWatched = 20L
        )
        val mirror = episode.copy(lastWatched = 19L)
        val raw = mapOf(
            "tt111" to mirror,
            "tt111_s1e3" to episode
        )

        val canonical = WatchProgressSyncReducer.canonicalizeForRemote(raw)

        assertFalse("tt111" in canonical)
        assertEquals(episode, canonical["tt111_s1e3"])
    }

    @Test
    fun `canonicalize keeps a series row that is actually a movie`() {
        val movie = progress(contentId = "tt222", position = 90_000L, lastWatched = 5L)

        val canonical = WatchProgressSyncReducer.canonicalizeForRemote(mapOf("tt222" to movie))

        assertEquals(movie, canonical["tt222"])
    }

    @Test
    fun `canonicalize drops empty remote-bound rows so they cannot wipe other devices`() {
        val empty = progress(contentId = "tt333", position = 0L, duration = 0L, lastWatched = 99L)
        val good = progress(contentId = "tt444", position = 120_000L, lastWatched = 10L)

        val canonical = WatchProgressSyncReducer.canonicalizeForRemote(
            mapOf("tt333" to empty, "tt444" to good)
        )

        assertFalse("tt333" in canonical)
        assertTrue("tt444" in canonical)
    }

    @Test
    fun `snapshot normalize synthesizes a series key from the latest episode`() {
        val s1e1 = episodeProgress("tt555", 1, 1, position = 10_000L, lastWatched = 1L)
        val s1e2 = episodeProgress("tt555", 1, 2, position = 250_000L, lastWatched = 5L)

        val normalized = WatchProgressSyncReducer.normalizePulledEntries(
            listOf(
                "tt555_s1e1" to s1e1,
                "tt555_s1e2" to s1e2
            )
        ).toMap()

        assertEquals(s1e2, normalized["tt555_s1e2"])
        assertEquals(s1e2, normalized["tt555"])
        assertEquals(s1e1, normalized["tt555_s1e1"])
    }

    @Test
    fun `snapshot normalize does not replace a newer series-level movie-like row`() {
        val episode = episodeProgress("tt666", 1, 1, position = 50_000L, lastWatched = 2L)
        val newerSeries = episode.copy(lastWatched = 9L)

        val normalized = WatchProgressSyncReducer.normalizePulledEntries(
            listOf(
                "tt666_s1e1" to episode,
                "tt666" to newerSeries
            )
        ).toMap()

        assertEquals(newerSeries, normalized["tt666"])
    }

    @Test
    fun `duplicate pulled keys keep the newest lastWatched`() {
        val older = progress(contentId = "tt777", position = 10_000L, lastWatched = 1L)
        val newer = progress(contentId = "tt777", position = 80_000L, lastWatched = 8L)

        val normalized = WatchProgressSyncReducer.normalizePulledEntries(
            listOf("tt777" to older, "tt777" to newer)
        )

        assertEquals(1, normalized.size)
        assertEquals(80_000L, normalized.single().second.position)
    }

    @Test
    fun `newer empty remote must not replace local in-progress position`() {
        val local = progress(position = 700_000L, duration = 2_400_000L, lastWatched = 10L)
        val remote = progress(position = 0L, duration = 0L, lastWatched = 99L)

        val decision = WatchProgressSyncReducer.shouldReplaceLocalWithRemote(
            local = local,
            remote = remote,
            lastSuccessfulPushMs = 0L
        )

        assertEquals(MergeDecision.KeepLocalEmptyRemote, decision)
    }

    @Test
    fun `newer remote with real position replaces local`() {
        val local = progress(position = 100_000L, duration = 2_400_000L, lastWatched = 10L)
        val remote = progress(position = 800_000L, duration = 2_400_000L, lastWatched = 20L)

        val decision = WatchProgressSyncReducer.shouldReplaceLocalWithRemote(
            local = local,
            remote = remote,
            lastSuccessfulPushMs = 0L
        )

        assertEquals(MergeDecision.AcceptRemote, decision)
    }

    @Test
    fun `unsynced local newer than last push is preserved against older remote`() {
        val local = progress(position = 500_000L, lastWatched = 50L)
        val remote = progress(position = 100_000L, lastWatched = 20L)

        val decision = WatchProgressSyncReducer.shouldReplaceLocalWithRemote(
            local = local,
            remote = remote,
            lastSuccessfulPushMs = 30L
        )

        assertEquals(MergeDecision.KeepLocalNewerUnsynced, decision)
    }

    @Test
    fun `already-synced equal timestamps keep local`() {
        val local = progress(position = 500_000L, lastWatched = 20L)
        val remote = progress(position = 500_000L, lastWatched = 20L)

        val decision = WatchProgressSyncReducer.shouldReplaceLocalWithRemote(
            local = local,
            remote = remote,
            lastSuccessfulPushMs = 20L
        )

        assertEquals(MergeDecision.KeepLocalAlreadySynced, decision)
    }

    @Test
    fun `missing remote row is preserved when it was created after last push`() {
        val local = progress(position = 300_000L, lastWatched = 80L)

        assertTrue(
            WatchProgressSyncReducer.shouldPreserveLocalMissingFromRemote(
                local = local,
                lastSuccessfulPushMs = 40L,
                isNonTraktId = null
            )
        )
        assertFalse(
            WatchProgressSyncReducer.shouldPreserveLocalMissingFromRemote(
                local = local,
                lastSuccessfulPushMs = 90L,
                isNonTraktId = null
            )
        )
    }

    @Test
    fun `non-trakt ids are preserved even when missing from a trakt remote snapshot`() {
        val local = progress(contentId = "kitsu:44", position = 300_000L, lastWatched = 1L)

        assertTrue(
            WatchProgressSyncReducer.shouldPreserveLocalMissingFromRemote(
                local = local,
                lastSuccessfulPushMs = 99L,
                isNonTraktId = { it.startsWith("kitsu:") }
            )
        )
    }

    @Test
    fun `full save-push-pull-resume loop keeps movie position`() {
        val saved = progress(contentId = "tt888", position = 1_111_000L, duration = 3_000_000L, lastWatched = 40L)
        val pushed = WatchProgressSyncReducer.canonicalizeForRemote(mapOf("tt888" to saved))
        val pulled = WatchProgressSyncReducer.normalizePulledEntries(pushed.toList())
        val mergedDecision = WatchProgressSyncReducer.shouldReplaceLocalWithRemote(
            local = saved,
            remote = pulled.single().second,
            lastSuccessfulPushMs = 40L
        )

        assertEquals(1_111_000L, pushed.getValue("tt888").position)
        assertEquals(1_111_000L, pulled.single().second.position)
        assertEquals(MergeDecision.KeepLocalAlreadySynced, mergedDecision)
    }

    @Test
    fun `full save-push-pull-resume loop keeps episode position and series mirror`() {
        val saved = episodeProgress("tt999", 3, 2, position = 222_000L, lastWatched = 12L)
        val pushed = WatchProgressSyncReducer.canonicalizeForRemote(mapOf("tt999_s3e2" to saved))
        val pulled = WatchProgressSyncReducer.normalizePulledEntries(pushed.toList()).toMap()

        assertEquals(222_000L, pulled.getValue("tt999_s3e2").position)
        assertEquals(222_000L, pulled.getValue("tt999").position)
        assertEquals(3, pulled.getValue("tt999").season)
        assertEquals(2, pulled.getValue("tt999").episode)
    }

    @Test
    fun `delta upsert of empty progress does not win over local in-progress`() {
        val local = episodeProgress("tt1000", 1, 1, position = 333_000L, lastWatched = 5L)
        val remoteEmpty = local.copy(position = 0L, duration = 0L, lastWatched = 6L)

        assertEquals(
            MergeDecision.KeepLocalEmptyRemote,
            WatchProgressSyncReducer.shouldReplaceLocalWithRemote(local, remoteEmpty, lastSuccessfulPushMs = 0L)
        )
    }

    private fun progress(
        contentId: String = "tt1234567",
        position: Long,
        duration: Long = 2_400_000L,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "movie",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = position,
        duration = duration,
        lastWatched = lastWatched
    )

    private fun episodeProgress(
        contentId: String,
        season: Int,
        episode: Int,
        position: Long,
        lastWatched: Long
    ) = WatchProgress(
        contentId = contentId,
        contentType = "series",
        name = contentId,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = "$contentId:$season:$episode",
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 1_800_000L,
        lastWatched = lastWatched
    )
}
