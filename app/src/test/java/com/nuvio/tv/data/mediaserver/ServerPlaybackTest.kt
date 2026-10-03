package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.player.StreamAutoPlaySelector
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerPlaybackTest {
    private val metaRepository = mockk<MetaRepository> {
        every { getCachedMeta(any(), any()) } returns null
    }
    private var now = 0L

    private class Harness(
        val provider: FakeServerProvider,
        val repository: ServerRepository,
        val connection: ServerConnection,
        val streams: ServerStreams,
        val playback: ServerPlayback
    )

    private fun harness(provider: FakeServerProvider = FakeServerProvider()): Harness {
        val (repository, connection) = fakeServerRepository(provider)
        val matcher = ServerMatcher(repository, mockk<TmdbService>(relaxed = true), metaRepository)
        return Harness(
            provider = provider,
            repository = repository,
            connection = connection,
            streams = ServerStreams(repository, matcher),
            playback = ServerPlayback(repository, CoroutineScope(Dispatchers.Unconfined)) { now }
        )
    }

    @Test
    fun nativeRequestsProduceOneAttributedSource() = runBlocking {
        val harness = harness()
        val videoId = ServerItemRef(harness.connection.id, "42").encode()

        val source = harness.streams.sources("movie", videoId, null, null).single()
        val stream = source.load().single()

        assertEquals("Fake · Box", source.name)
        assertTrue(source.preferred)
        assertEquals("Fake · Box", stream.addonName)
        assertEquals(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"), stream.serverTarget)
        assertNull(stream.getStreamUrl())
        assertFalse(stream.isTorrent())
        assertEquals("item-42.mkv", stream.behaviorHints?.filename)
    }

    @Test
    fun serverItemsAndMatchableCatalogTitlesArePlayable() {
        val harness = harness()
        val streams = harness.streams
        assertTrue(streams.canServe("movie", ServerItemRef(harness.connection.id, "42").encode()))
        assertTrue(streams.canServe("movie", "tt0111161"))
        assertTrue(streams.canServe("series", "tt0944947:1:1"))
        assertFalse(streams.canServe("movie", "someaddon:1"))
        assertFalse(streams.canServe("movie", ServerItemRef("cmissing", "42").encode()))

        harness.repository.setEnabled(harness.connection.id, false)
        assertFalse(streams.canServe("movie", ServerItemRef(harness.connection.id, "42").encode()))
        assertFalse(streams.canServe("movie", "tt0111161"))
    }

    @Test
    fun catalogMetadataListsServerFilesFirst() {
        val harness = harness()
        assertTrue(harness.streams.preferredSourceNames("movie", "tt0111161").isEmpty())

        harness.repository.setCatalogMetadata(harness.connection.id, true)
        val preferred = harness.streams.preferredSourceNames("movie", "tt0111161")
        assertEquals(setOf("Fake · Box"), preferred)

        val ordered = StreamAutoPlaySelector.orderAddonStreams(
            streams = listOf(AddonStreams("Addon", null, emptyList()), AddonStreams("Fake · Box", null, emptyList())),
            installedOrder = listOf("Addon"),
            preferredNames = preferred
        )
        assertEquals(listOf("Fake · Box", "Addon"), ordered.map { it.addonName })
    }

    @Test
    fun autoplayCanChooseServerCandidates() = runBlocking {
        val harness = harness()
        val stream = harness.streams.candidates(ServerItemRef(harness.connection.id, "42")).single()

        val selected = StreamAutoPlaySelector.selectAutoPlayStream(
            streams = listOf(stream),
            mode = StreamAutoPlayMode.FIRST_STREAM,
            regexPattern = "",
            source = StreamAutoPlaySource.INSTALLED_ADDONS_ONLY,
            installedAddonNames = emptySet(),
            selectedAddons = emptySet(),
            selectedPlugins = setOf("Some plugin")
        )
        assertEquals(stream, selected)
    }

    @Test
    fun reportsLifecycleInOrderAndStopsOnce() = runBlocking {
        val harness = harness()
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        val url = session.url
        assertTrue(playback.isServerSource(url))

        playback.onPlaybackSnapshot(url, 0L, isPlaying = false, isLoading = true, isEnded = false)
        playback.onPlaybackSnapshot(url, 0L, isPlaying = true, isLoading = false, isEnded = false)
        now += 1_000L
        playback.onPlaybackSnapshot(url, 1_000L, isPlaying = true, isLoading = false, isEnded = false)
        now += 11_000L
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = false, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 12_000L, isPlaying = true, isLoading = false, isEnded = false)
        playback.onPlaybackSnapshot(url, 13_000L, isPlaying = false, isLoading = false, isEnded = true)
        playback.stop(url)

        assertEquals(
            listOf(
                ServerPlaybackEventType.START,
                ServerPlaybackEventType.PROGRESS,
                ServerPlaybackEventType.PAUSE,
                ServerPlaybackEventType.RESUME,
                ServerPlaybackEventType.STOP
            ),
            harness.provider.reported.toList()
        )
        assertFalse(playback.isServerSource(url))
    }

    @Test
    fun preparingAnotherSourceDropsUnstartedSessions() = runBlocking {
        val harness = harness()
        val first = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "1"), "src-1"))
        val second = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "2"), "src-2"))

        assertFalse(harness.playback.isServerSource(first.url))
        assertTrue(harness.playback.isServerSource(second.url))
    }

    @Test
    fun directPlayFailureFallsBackOnlyOnce() = runBlocking {
        val provider = FakeServerProvider()
        val harness = harness(provider)
        val session = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))

        assertTrue(harness.playback.canFallback(session.url))
        assertNull(harness.playback.fallback(session.url))
        assertFalse(provider.playbackRequests.last().capabilities.allowDirectPlay)
        assertTrue(harness.playback.isServerSource(session.url))
    }

    @Test
    fun firstRequestAllowsDirectPlay() = runBlocking {
        val provider = FakeServerProvider()
        val harness = harness(provider)

        harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7"))

        assertTrue(provider.playbackRequests.single().capabilities.allowDirectPlay)
    }

    @Test
    fun switchesTranscodeAudioByRestartingTheSession() = runBlocking {
        val provider = FakeServerProvider(transcodes = true)
        val harness = harness(provider)
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        assertFalse(playback.canFallback(session.url))
        assertEquals(listOf(1 to true, 2 to false), playback.audioTracks(session.url).map { it.index to it.selected })

        val switched = playback.switchAudio(session.url, 2)!!

        val request = provider.playbackRequests.last()
        assertEquals(2, request.audioStreamIndex)
        assertFalse(request.capabilities.allowDirectPlay)
        assertFalse(playback.isServerSource(session.url))
        assertTrue(playback.isServerSource(switched.url))
        assertEquals(listOf(1 to false, 2 to true), playback.audioTracks(switched.url).map { it.index to it.selected })
        assertTrue(ServerPlaybackEventType.STOP in provider.reported)
    }

    @Test
    fun burnsInSubtitlesByRestartingTheTranscode() = runBlocking {
        val provider = FakeServerProvider(transcodes = true)
        val harness = harness(provider)
        val playback = harness.playback
        val session = playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "42"), "src-42"))
        val japanese = playback.switchAudio(session.url, 2)!!

        val burnedIn = playback.switchSubtitle(japanese.url, 3)!!

        val request = provider.playbackRequests.last()
        assertEquals(3, request.subtitleStreamIndex)
        assertEquals(2, request.audioStreamIndex)
        assertFalse(request.capabilities.allowDirectPlay)
        assertEquals(listOf(3), playback.burnInSubtitles(burnedIn.url).filter { it.selected }.map { it.index })

        val english = playback.switchAudio(burnedIn.url, 1)!!
        assertEquals(3, provider.playbackRequests.last().subtitleStreamIndex)

        val cleared = playback.switchSubtitle(english.url, null)!!
        assertNull(provider.playbackRequests.last().subtitleStreamIndex)
        assertTrue(playback.burnInSubtitles(cleared.url).none { it.selected })
    }

    @Test
    fun readsTheServerResumePointForThePlayingItem() = runBlocking {
        val provider = FakeServerProvider().apply {
            userStates["7"] = listOf(ServerUserState("v7", positionMs = 30_000L, durationMs = 60_000L, played = false, lastPlayedEpochMs = 5_000L))
        }
        val harness = harness(provider)
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertEquals(30_000L, harness.playback.resumeState(url)?.positionMs)
        assertNull(harness.playback.resumeState("https://other.example/7"))
    }

    @Test
    fun directPlayLeavesTracksToThePlayer() = runBlocking {
        val harness = harness()
        val url = harness.playback.prepare(ServerPlaybackTarget(ServerItemRef(harness.connection.id, "7"), "src-7")).url

        assertTrue(harness.playback.audioTracks(url).isEmpty())
        assertTrue(harness.playback.burnInSubtitles(url).isEmpty())
        assertNull(harness.playback.switchAudio(url, 2))
        assertNull(harness.playback.switchSubtitle(url, 3))
        assertTrue(harness.playback.isServerSource(url))
    }
}
