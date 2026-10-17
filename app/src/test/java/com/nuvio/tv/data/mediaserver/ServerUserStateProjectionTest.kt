package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.WatchedItem
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ServerUserStateProjectionTest {
    private val server = fakeServerRepository()
    private val connection = server.second
    private val progressRepository = mockk<WatchProgressRepository>(relaxed = true)
    private val progressPreferences = mockk<WatchProgressPreferences>()
    private val watchedPreferences = mockk<WatchedItemsPreferences>(relaxed = true)
    private val projection = ServerUserStateProjection(
        catalog = mockk(relaxed = true),
        repository = server.first,
        watchProgressRepository = progressRepository,
        watchProgressPreferences = progressPreferences,
        watchedItemsPreferences = watchedPreferences,
        profileManager = mockk { every { activeProfileId } returns MutableStateFlow(1) }
    )

    private val seriesId = ServerItemRef(connection.id, FakeServerProvider.SHOW_ID).encode()

    private fun state(episode: Int, played: Boolean, positionMs: Long = 0L, lastPlayed: Long? = 1_000L) = ServerUserState(
        videoId = ServerItemRef(connection.id, "ep$episode").encode(),
        positionMs = positionMs,
        durationMs = 60_000L,
        played = played,
        lastPlayedEpochMs = lastPlayed,
        season = 1,
        episode = episode,
        title = "Episode $episode"
    )

    @Test
    fun importsNothingUntilEnabled() = runBlocking {
        val details = ServerItemDetails(
            meta = meta(seriesId, ServerMediaKind.SERIES, "Show"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(state(1, played = true), state(2, played = false, positionMs = 30_000L))
        )

        projection.apply(details)

        coVerify(exactly = 0) { watchedPreferences.markAsWatchedBatch(any(), any()) }
        coVerify(exactly = 0) { progressRepository.saveProgressBatch(any(), any()) }
    }

    @Test
    fun mirrorsServerStateIntoLocalHistory() = runBlocking {
        server.first.setImportWatchState(connection.id, true)
        every { watchedPreferences.getWatchedEpisodesForContent(seriesId, 1) } returns flowOf(setOf(1 to 2, 1 to 3))
        every { progressPreferences.getEpisodeProgress(seriesId, 1, 4, 1) } returns flowOf(null)
        every { progressPreferences.getEpisodeProgress(seriesId, 1, 5, 1) } returns flowOf(
            WatchProgress(seriesId, "series", "Show", null, null, null, "v", 1, 5, null, 1L, 2L, lastWatched = 9_000L)
        )
        val details = ServerItemDetails(
            meta = meta(seriesId, ServerMediaKind.SERIES, "Show"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(
                state(1, played = true),
                state(2, played = true),
                state(3, played = false),
                state(4, played = false, positionMs = 30_000L),
                state(5, played = false, positionMs = 10_000L),
                state(6, played = false, positionMs = 10_000L, lastPlayed = null)
            )
        )

        projection.apply(details)

        val marked = slot<List<WatchedItem>>()
        coVerify { watchedPreferences.markAsWatchedBatch(capture(marked), 1) }
        assertEquals(listOf(1 to 1), marked.captured.map { it.season to it.episode })
        coVerify { watchedPreferences.unmarkAsWatchedBatch(seriesId, listOf(1 to 3), 1) }

        val saved = slot<List<WatchProgress>>()
        coVerify { progressRepository.saveProgressBatch(capture(saved), false) }
        assertEquals(listOf(4), saved.captured.map { it.episode })
        assertEquals(30_000L, saved.captured.single().position)
        assertEquals(ServerCatalog.baseUrl(connection.id), saved.captured.single().addonBaseUrl)
    }

    @Test
    fun ignoresItemsFromUnknownConnections() = runBlocking {
        val details = ServerItemDetails(
            meta = meta(ServerItemRef("missing", "1").encode(), ServerMediaKind.MOVIE, "Film"),
            externalIds = TrackingExternalIds(),
            userStates = listOf(state(1, played = true))
        )

        projection.apply(details)

        coVerify(exactly = 0) { watchedPreferences.markAsWatchedBatch(any(), any()) }
        coVerify(exactly = 0) { progressRepository.saveProgressBatch(any(), any()) }
    }
}
