package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the writer sends when a run is dropped, and what it says about it.
 *
 * Closing is a change of the session state, not a viewing: the write names the session and moves it
 * to `closed`, and it must not carry an episode, or Simkl would read it as a watch of that episode
 * and the 2-day rule would decide what to do with it. The state is read back from the account, which
 * is also what Continue Watching reads.
 */
class SimklRewatchWriterTest {

    private val syncRepository = mockk<SimklSyncRepository>(relaxed = true)

    @Test
    fun `closing a run names its session and moves it to closed`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val remote = FakeRemote(emptyList())
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        assertTrue(writer.closeRewatchSession(show(), REWATCH_ID))

        assertEquals(listOf("/sync/history"), engine.paths)
        assertTrue("allow_rewatch=yes" in engine.urls.single())
        assertTrue("\"rewatch_id\":$REWATCH_ID" in engine.bodies.single())
        assertTrue("\"rewatch_status\":\"closed\"" in engine.bodies.single())
        assertTrue("\"is_rewatch\":true" in engine.bodies.single())
        // No watch: no episode coordinates, no date and no status mark.
        assertFalse("\"seasons\"" in engine.bodies.single())
        assertFalse("\"episodes\"" in engine.bodies.single())
        assertFalse("watched_at" in engine.bodies.single())
        assertFalse("\"status\"" in engine.bodies.single())
        // The state is read back in the background, seconds later, and not waited for.
        assertEquals(0, remote.reads.get())
    }

    @Test
    fun `a write the account refused is reported as not closed`() = runBlocking {
        val engine = RecordingEngine(response(403))
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), FakeRemote(emptyList()), syncRepository)

        assertFalse(writer.closeRewatchSession(show(), REWATCH_ID))
    }

    @Test
    fun `the sessions are read back after the write`() = runBlocking {
        val engine = RecordingEngine(response(200))
        val remote = FakeRemote(emptyList())
        val writer = SimklRewatchWriter(SimklMutationService(client(engine)), remote, syncRepository)

        writer.closeRewatchSession(show(), REWATCH_ID)

        // The read runs on the writer's own scope with the delays Simkl needs, so it has not happened
        // by the time the answer is given. What matters here is that nothing else was written.
        assertEquals(1, engine.paths.size)
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

    private fun show() = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856")
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

    /** The account as the writer reads it. A read is counted, so a test can tell whether one waited. */
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
        const val REWATCH_ID = 7482L

        val configuration = SimklApiConfiguration("client-id", "nuvio", "1.0")
        fun response(status: Int, body: String = "{}") = SimklRawHttpResponse(status, body)
    }
}
