package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerWatchedTest {
    private var failureNotices = 0

    private class Harness(
        val provider: FakeServerProvider,
        val repository: ServerRepository,
        val connection: ServerConnection,
        val watched: ServerWatched,
        val catalog: ServerCatalog
    )

    private fun harness(provider: FakeServerProvider = provider()): Harness {
        val (repository, connection) = fakeServerRepository(provider)
        val tmdbService = mockk<TmdbService> {
            coEvery { ensureTmdbId(any(), any(), any()) } returns null
            coEvery { tmdbToImdb(any(), any()) } returns null
        }
        val metaRepository = mockk<MetaRepository> { every { getCachedMeta(any(), any()) } returns null }
        val addonRepository = mockk<AddonRepository> { every { getInstalledAddons() } returns flowOf(emptyList()) }
        val catalog = ServerCatalog(mockk<Context>(relaxed = true), repository, addonRepository)
        val watched = ServerWatched(
            repository = repository,
            matcher = ServerMatcher(repository, tmdbService, metaRepository),
            catalog = catalog,
            scope = CoroutineScope(Dispatchers.Default)
        ) { failureNotices++ }
        return Harness(provider, repository, connection, watched, catalog)
    }

    private fun provider() = FakeServerProvider().apply {
        indexedIds["7"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
        indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947", tmdb = 1399)
        episodes[1 to 2] = ServerEpisode(itemId = "501", premiereDate = null)
    }

    private fun movie(id: String) = ServerWatchMark(id, "movie", id, null, null)

    private fun episode(connectionId: String, number: Int) = ServerWatchMark(
        contentId = ServerItemRef(connectionId, FakeServerProvider.SHOW_ID).encode(),
        contentType = "series",
        videoId = ServerItemRef(connectionId, "ep$number").encode(),
        season = 1,
        episode = number
    )

    @Test
    fun mirrorsCatalogMarksWhenAddonMetadataIsOn() = runBlocking {
        val harness = harness()
        harness.repository.setCatalogMetadata(harness.connection.id, true)
        val episode = ServerWatchMark("tt0944947", "series", "tt0944947:1:2", 1, 2)

        withTimeout(15_000L) { harness.watched.mirror(listOf(movie("tt0111161"), episode, movie("tt9999999")), played = true)!!.join() }
        assertEquals(setOf("7" to true, "501" to true), harness.provider.playedChanges.toSet())

        withTimeout(15_000L) { harness.watched.mirror(listOf(movie("tt0111161")), played = false)!!.join() }
        assertEquals("7" to false, harness.provider.playedChanges.last())
        assertEquals(3, harness.provider.playedChanges.size)
        assertEquals(0, failureNotices)
    }

    @Test
    fun leavesServerAloneWhenAddonMetadataIsOff() {
        val harness = harness()
        assertNull(harness.watched.mirror(listOf(movie("tt0111161")), played = true))
    }

    @Test
    fun serverItemsKeepTheirDirectPath() {
        val harness = harness()
        harness.repository.setCatalogMetadata(harness.connection.id, true)
        assertNull(harness.watched.mirror(listOf(movie(ServerItemRef(harness.connection.id, "7").encode())), played = true))
    }

    @Test
    fun writesMoviesAndEpisodesButNotWholeSeries() = runBlocking {
        val harness = harness()
        val id = harness.connection.id
        val series = ServerWatchMark(ServerItemRef(id, FakeServerProvider.SHOW_ID).encode(), "series", null, null, null)
        val marks = listOf(movie(ServerItemRef(id, "7").encode()), series, episode(id, 1), movie("tt0111161"))

        withTimeout(15_000L) { harness.watched.apply(marks, played = true) { }!!.join() }

        assertEquals(setOf("7" to true, "ep1" to true), harness.provider.playedChanges.toSet())
        assertNull(harness.watched.apply(listOf(series, movie("tt0111161")), played = true) { })
    }

    @Test
    fun stopsWritingAfterAFailedBatch() = runBlocking {
        val harness = harness(provider().apply { failingPlayed += "ep1" })
        val id = harness.connection.id
        val targets = (1..8).map { number ->
            ServerItemRef(id, "ep$number") to episode(id, number)
        }

        val failed = harness.watched.write(targets, played = true)

        assertEquals(listOf("ep1", "ep7", "ep8"), failed.map { ServerItemRef.parse(it.videoId)!!.itemId })
        assertEquals((1..6).map { "ep$it" }.toSet(), harness.provider.playedChanges.map { it.first }.toSet())
    }

    @Test
    fun failedWritesRollBackAndResync() = runBlocking {
        val harness = harness(provider().apply { failingPlayed += "7" })
        val movie = movie(ServerItemRef(harness.connection.id, "7").encode())
        val rolledBack = mutableListOf<ServerWatchMark>()
        var resynced = 0
        val collector = CoroutineScope(Dispatchers.Default).launch(start = CoroutineStart.UNDISPATCHED) {
            harness.catalog.detailsLoaded.collect { resynced++ }
        }

        withTimeout(15_000L) { harness.watched.apply(listOf(movie), played = true) { rolledBack += it }!!.join() }

        assertEquals(listOf(movie), rolledBack)
        assertEquals(1, failureNotices)
        withTimeout(5_000L) { while (resynced == 0) delay(10) }
        assertTrue(resynced > 0)
        collector.cancel()
    }
}
