package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the writer answers for a rewatch the user confirmed.
 *
 * The verdict is the write, not a read of the account, and the account is only looked at when the
 * write itself came back as an error. Simkl answers a repeat viewing with a conflict whose meaning is
 * that the session was opened, so that conflict cannot be read as a refusal, and an episode the
 * history already holds is answered that way every time.
 */
class SimklRewatchWriterTest {

    private val syncRepository = mockk<SimklSyncRepository>(relaxed = true)

    @Test
    fun `a write the account took is the answer`() = runBlocking {
        val engine = RecordingEngine(response(201, ADDED_STATUS))
        val remote = FakeRemote(emptyList())
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        assertTrue(writer.recordConfirmedRewatch(episode(), WATCHED_AT))

        assertEquals(listOf("/sync/history"), engine.paths)
        // The write that is allowed to open a session, carrying the episode the user watched.
        assertTrue("allow_rewatch=yes" in engine.urls.single())
        assertTrue("\"is_rewatch\":true" in engine.bodies.single())
        assertTrue("\"number\":7" in engine.bodies.single())
        // The answer does not wait for the account. The refresh reads on its own, seconds later.
        assertEquals(0, remote.reads.get())
    }

    @Test
    fun `an episode the history already holds is a session, not a refusal`() = runBlocking {
        val engine = RecordingEngine(response(409, NOT_FOUND_CONFLICT))
        val remote = FakeRemote(emptyList())
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        assertTrue(writer.recordConfirmedRewatch(episode(), WATCHED_AT))

        assertTrue("allow_rewatch=yes" in engine.urls.single())
        assertEquals(0, remote.reads.get())
    }

    @Test
    fun `a write that came back as an error is answered by the account`() = runBlocking {
        val engine = RecordingEngine(response(403))
        val remote = FakeRemote(listOf(sessionRow()))
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        assertTrue(writer.recordConfirmedRewatch(episode(), WATCHED_AT))

        assertTrue(remote.reads.get() >= 1)
    }

    @Test
    fun `a rewatch the account does not carry is reported as not recorded`() = runBlocking {
        val engine = RecordingEngine(response(403))
        val remote = FakeRemote(emptyList())
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        assertFalse(writer.recordConfirmedRewatch(episode(), WATCHED_AT))

        assertTrue(remote.reads.get() >= 1)
    }

    private fun client(engine: RecordingEngine): SimklApiClient = SimklApiClient(
        engine = engine,
        configuration = configuration,
        authorization = { testSimklAuthorization() },
        onUnauthorized = {},
        nowEpochMs = { 0L },
        sleep = {},
        retryJitterMs = { 0L }
    )

    private fun episode() = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856"),
        episode = TrackingEpisode(season = 2, number = 7)
    )

    /** The sidecar row Simkl publishes for a rewatch, holding the episode the user confirmed. */
    private fun sessionRow() = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        isRewatch = true,
        show = SimklMedia(
            title = "Dark",
            year = 2017,
            ids = mapOf("imdb" to JsonPrimitive("tt5753856"))
        ),
        seasons = listOf(
            SimklSeason(number = 2, episodes = listOf(SimklEpisode(number = 7, watchedAt = WATCHED_AT_ISO)))
        )
    )

    private class RecordingEngine(vararg responses: SimklRawHttpResponse) : SimklHttpEngine {
        private val queued = responses.toMutableList()
        val urls = mutableListOf<String>()
        val bodies = mutableListOf<String>()

        val paths: List<String>
            get() = urls.map { url -> url.substringAfter("api.simkl.com").substringBefore('?') }

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String
        ): SimklRawHttpResponse {
            urls += url
            bodies += body
            return queued.removeAt(0)
        }
    }

    /**
     * The account as the writer reads it. A read is counted, so a test can tell whether the answer
     * waited for one.
     */
    private class FakeRemote(private val sessions: List<SimklLibraryEntry>) : SimklSyncRemote {
        val reads = AtomicInteger()

        override suspend fun fetchActivities(): SimklActivities = SimklActivities()

        override suspend fun fetchAllItems(request: SimklAllItemsRequest): SimklAllItemsResponse =
            SimklAllItemsResponse()

        override suspend fun fetchPlayback(): List<SimklPlaybackSession> = emptyList()

        override suspend fun fetchRewatchSessions(): List<SimklLibraryEntry> {
            reads.incrementAndGet()
            return sessions
        }
    }

    private companion object {
        const val WATCHED_AT = 1_700_000_000_000L
        const val WATCHED_AT_ISO = "2023-11-14T22:13:20Z"

        const val ADDED_STATUS =
            """{"added":{"statuses":[{"request":{"title":"Dark","ids":{"imdb":"tt5753856"}},""" +
                """"response":{"status":"watching","simkl_type":"tv"}}]},""" +
                """"not_found":{"movies":[],"shows":[],"episodes":[]}}"""

        /** What Simkl answers an episode the history already holds. */
        const val NOT_FOUND_CONFLICT =
            """{"not_found":{"movies":[],"shows":[{"title":"Dark","ids":{"imdb":"tt5753856"}}],"episodes":[]}}"""

        val configuration = SimklApiConfiguration("client-id", "nuvio", "1.0")
        fun response(status: Int, body: String = "{}") = SimklRawHttpResponse(status, body)
    }
}
