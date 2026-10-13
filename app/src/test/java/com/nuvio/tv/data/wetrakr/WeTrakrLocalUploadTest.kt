package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.SavedLibraryItem
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WeTrakrLocalUploadTest {
    private val snapshot = WeTrakrSyncSnapshot(
        accountId = 1,
        watched = listOf(
            WeTrakrWatchedRecord(WeTrakrItemType.MOVIE, weTrakrMovie("tt0000001"), "2026-01-01T00:00:00Z"),
            WeTrakrWatchedRecord(
                WeTrakrItemType.EPISODE,
                WeTrakrMedia(WeTrakrIds(imdb = "tt0000009", tmdb = 90)),
                "2026-01-01T00:00:00Z",
                season = 1,
                episode = 2
            )
        ),
        watchlist = listOf(WeTrakrWatchlistItem(WeTrakrItemType.SHOW, WeTrakrMedia(WeTrakrIds(tmdb = 55)))),
        isInitialized = true
    )

    @Test
    fun `watched history skips what WeTrakr has and keeps the local watch date`() {
        val preview = weTrakrUploadPreview(
            watched = listOf(
                watched("tt0000001", "movie"),
                watched("tt0000002", "movie", watchedAt = 1_700_000_000_000L),
                watched("tt0000002", "movie"),
                watched("tmdb:90", "series", season = 1, episode = 2),
                watched("tt0000009", "series", season = 1, episode = 3),
                watched("tt0000009", "series")
            ),
            library = emptyList(),
            snapshot = snapshot
        )

        val group = preview.watched
        assertEquals(2, group.alreadyOnWeTrakr)
        assertEquals(
            listOf("MOVIE tt0000002 null null", "EPISODE tt0000009 1 3"),
            group.changes.map { "${it.target.type} ${it.target.media.ids.contentId} ${it.target.season} ${it.target.episode}" }
        )
        assertEquals("2023-11-14T22:13:20Z", group.changes.first().trackedAt)
        assertEquals(listOf(WeTrakrUnmatchedTitle(WeTrakrUploadKind.WATCHED, "tt0000009", 1)), group.unmatched)
    }

    @Test
    fun `titles without a WeTrakr id are grouped as unmatched`() {
        val preview = weTrakrUploadPreview(
            watched = listOf(
                watched("kitsu:12", "series", title = "Frieren", season = 1, episode = 1),
                watched("kitsu:12", "series", title = "Frieren", season = 1, episode = 2)
            ),
            library = listOf(saved("kitsu:12", "anime", "Frieren"), saved("tt0000004", "tv", "News channel")),
            snapshot = snapshot
        )

        assertEquals(listOf(WeTrakrUnmatchedTitle(WeTrakrUploadKind.WATCHED, "Frieren", 2)), preview.watched.unmatched)
        assertEquals(
            listOf(
                WeTrakrUnmatchedTitle(WeTrakrUploadKind.LIBRARY, "Frieren", 1),
                WeTrakrUnmatchedTitle(WeTrakrUploadKind.LIBRARY, "News channel", 1)
            ),
            preview.library.unmatched
        )
        assertEquals(0, preview.watched.toUpload + preview.library.toUpload)
    }

    @Test
    fun `library skips titles already planned, watched or playing on WeTrakr`() {
        val preview = weTrakrUploadPreview(
            watched = emptyList(),
            library = listOf(
                saved("tmdb:55", "series", "Planned show"),
                saved("tt0000001", "movie", "Watched movie"),
                saved("tt0000009", "series", "Show in progress"),
                saved("tt0000003", "movie", "New movie", addedAt = 1_700_000_000_000L)
            ),
            snapshot = snapshot
        )

        assertEquals(3, preview.library.alreadyOnWeTrakr)
        val change = preview.library.changes.single()
        assertEquals(WeTrakrItemType.MOVIE, change.target.type)
        assertEquals("tt0000003", change.target.media.ids.contentId)
        assertEquals("2023-11-14T22:13:20Z", change.trackedAt)
    }

    @Test
    fun `request count follows the batch size of the selected kinds`() {
        val many = (1..WETRAKR_UPLOAD_BATCH_SIZE + 1).map { watched("tt1%06d".format(it), "movie") }
        val preview = weTrakrUploadPreview(many, listOf(saved("tt0000003", "movie", "New movie")), snapshot)

        assertEquals(2, preview.requestCount(setOf(WeTrakrUploadKind.WATCHED)))
        assertEquals(3, preview.requestCount(WeTrakrUploadKind.entries.toSet()))
        assertEquals(0, preview.requestCount(emptySet()))
        assertNull(preview.watched.changes.first().trackedAt)
    }

    @Test
    fun `completed watch progress counts as watched history`() {
        val preview = weTrakrUploadPreview(
            watched = listOf(watched("tt0000005", "movie", watchedAt = 1_700_000_000_000L)),
            library = emptyList(),
            snapshot = snapshot,
            progress = listOf(
                progress("tt0000005", "movie", position = 95, lastWatched = 1_800_000_000_000L),
                progress("tt0000006", "series", season = 2, episode = 4, position = 92, lastWatched = 1_700_000_000_000L),
                progress("tt0000007", "movie", position = 40),
                progress("tt0000009", "series", season = 1, episode = 2, position = 99)
            )
        )

        val group = preview.watched
        assertEquals(1, group.alreadyOnWeTrakr)
        assertEquals(
            listOf("MOVIE tt0000005 null null 2023-11-14T22:13:20Z", "EPISODE tt0000006 2 4 2023-11-14T22:13:20Z"),
            group.changes.map {
                "${it.target.type} ${it.target.media.ids.contentId} ${it.target.season} ${it.target.episode} ${it.trackedAt}"
            }
        )
    }

    private fun progress(
        id: String,
        type: String,
        season: Int? = null,
        episode: Int? = null,
        position: Long,
        lastWatched: Long = 0L
    ) = WatchProgress(
        contentId = id,
        contentType = type,
        name = id,
        poster = null,
        backdrop = null,
        logo = null,
        videoId = id,
        season = season,
        episode = episode,
        episodeTitle = null,
        position = position,
        duration = 100,
        lastWatched = lastWatched
    )

    private fun watched(
        id: String,
        type: String,
        title: String = id,
        season: Int? = null,
        episode: Int? = null,
        watchedAt: Long = 0L
    ) = WatchedItem(id, type, title, season, episode, watchedAt)

    private fun saved(id: String, type: String, name: String, addedAt: Long = 0L) = SavedLibraryItem(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList(),
        addonBaseUrl = null,
        addedAt = addedAt
    )
}
