package com.nuvio.tv.data.wetrakr

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class WeTrakrSyncEngineTest {
    private val first = WeTrakrActivities(
        mapOf(
            "all" to "2026-09-20T10:00:00.000Z",
            "movies.all" to "2026-09-20T10:00:00.000Z",
            "movies.last_tracking_watched_at" to "2026-09-20T10:00:00.000Z",
            "episodes.last_tracking_watched_at" to "2026-09-19T10:00:00.000Z"
        )
    )

    private suspend fun initialSnapshot(remote: WeTrakrTestRemote): WeTrakrSyncSnapshot {
        remote.activities = first
        remote.watched[WeTrakrItemType.MOVIE] = listOf(WeTrakrWatchedRecord(WeTrakrItemType.MOVIE, weTrakrMovie("tt1"), "2026-09-20T10:00:00.000Z"))
        val snapshot = WeTrakrSyncEngine(remote) { 1L }.synchronize(WeTrakrSyncSnapshot(1))
        remote.calls.clear()
        return snapshot
    }

    @Test
    fun `first sync reads every section once`() = runTest {
        val remote = WeTrakrTestRemote(activities = first)

        WeTrakrSyncEngine(remote) { 1L }.synchronize(WeTrakrSyncSnapshot(1))

        assertEquals(
            listOf(
                "activities", "watched:MOVIE:all", "watched:EPISODE:all",
                "playback:MOVIE", "playback:EPISODE", "watchlist:MOVIE", "watchlist:SHOW"
            ),
            remote.calls
        )
    }

    @Test
    fun `unchanged activities cost a single call`() = runTest {
        val remote = WeTrakrTestRemote()
        val snapshot = initialSnapshot(remote)

        val next = WeTrakrSyncEngine(remote) { 2L }.synchronize(snapshot)

        assertEquals(listOf("activities"), remote.calls)
        assertEquals(snapshot.watched, next.watched)
        assertEquals(2L, next.checkedAtEpochMs)
    }

    @Test
    fun `new plays are fetched since the previous mark and merged`() = runTest {
        val remote = WeTrakrTestRemote()
        val snapshot = initialSnapshot(remote)
        remote.activities = WeTrakrActivities(
            first.values + mapOf(
                "movies.all" to "2026-09-21T10:00:00.000Z",
                "movies.last_tracking_watched_at" to "2026-09-21T10:00:00.000Z"
            )
        )
        remote.watched[WeTrakrItemType.MOVIE] = listOf(WeTrakrWatchedRecord(WeTrakrItemType.MOVIE, weTrakrMovie("tt2"), "2026-09-21T10:00:00.000Z"))

        val next = WeTrakrSyncEngine(remote) { 2L }.synchronize(snapshot)

        assertEquals("watched:MOVIE:2026-09-20T09:59:00Z", remote.calls[1])
        assertEquals(setOf("tt1", "tt2"), next.watched.mapNotNull { it.media.ids.imdb }.toSet())
        assertEquals(listOf("activities", "watched:MOVIE:2026-09-20T09:59:00Z", "playback:MOVIE", "playback:EPISODE"), remote.calls)
    }

    @Test
    fun `removals reload the whole watched list`() = runTest {
        val remote = WeTrakrTestRemote()
        val snapshot = initialSnapshot(remote)
        remote.activities = WeTrakrActivities(first.values + ("movies.last_tracking_removed_at" to "2026-09-21T10:00:00.000Z"))
        remote.watched[WeTrakrItemType.MOVIE] = emptyList()

        val next = WeTrakrSyncEngine(remote) { 2L }.synchronize(snapshot)

        assertEquals(true, "watched:MOVIE:all" in remote.calls)
        assertEquals(true, "watchlist:MOVIE" in remote.calls)
        assertEquals(emptyList<WeTrakrWatchedRecord>(), next.watched)
    }

    @Test
    fun `planning changes reload the watchlist only`() = runTest {
        val remote = WeTrakrTestRemote()
        val snapshot = initialSnapshot(remote)
        remote.activities = WeTrakrActivities(first.values + ("shows.last_tracking_planning_at" to "2026-09-21T10:00:00.000Z"))

        WeTrakrSyncEngine(remote) { 2L }.synchronize(snapshot)

        assertEquals(listOf("activities", "watchlist:MOVIE", "watchlist:SHOW"), remote.calls)
    }
}
