package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The write path of a rewatch: the body Simkl is sent, the query flag that lets the account open a
 * session, how a conflict it answers is read, and where the completion threshold lands in the result.
 *
 * The pieces are tested where they live, because that is where they can go wrong: the history body is
 * built in [buildSimklHistoryMutationBody], the flag is put on the request by [SimklMutationService],
 * and the threshold is read back by `toSimklScrobbleResult`.
 */
class SimklRewatchWriteTest {

    @Test
    fun `a rewatch write is an episode write and only it carries the flag`() {
        val rewatch = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(show(episode = TrackingEpisode(season = 2, number = 7)), WATCHED_AT)),
            isRewatch = true
        ).asObject()

        val showItem = rewatch.getValue("shows").jsonArray.single().jsonObject
        assertTrue(showItem.getValue("is_rewatch").jsonPrimitive.content.toBoolean())
        // A whole-series write marks every episode of the show and loses the session the user just
        // confirmed, so the rewatch body must be the episode branch: the coordinates, and no status.
        assertNull(showItem["status"])
        val episode = showItem.getValue("seasons").jsonArray.single().jsonObject
            .getValue("episodes").jsonArray.single().jsonObject
        assertEquals(7, episode.getValue("number").jsonPrimitive.content.toInt())

        val movieItem = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(movie(), WATCHED_AT)),
            isRewatch = true
        ).asObject().getValue("movies").jsonArray.single().jsonObject
        assertTrue(movieItem.getValue("is_rewatch").jsonPrimitive.content.toBoolean())

        // Nothing else carries it. A plain history write omits the flag, and a removal sends false,
        // which is not serialized at all: the flag is only ever sent as true.
        val plain = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(show(episode = TrackingEpisode(season = 2, number = 7)), WATCHED_AT))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertNull(plain["is_rewatch"])

        val removal = buildSimklHistoryRemovalBody(
            listOf(show(episode = TrackingEpisode(season = 2, number = 7)))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertNull(removal["is_rewatch"])
        assertNull(removal["status"])
    }

    @Test
    fun `the rewatch write asks for a rewatch and reads the conflict as the session it is`() = runBlocking {
        val engine = RecordingEngine(response(409, NOT_FOUND_CONFLICT))
        val service = SimklMutationService(client(engine))

        val result = service.addToHistory(
            items = listOf(
                TrackingHistoryItem(show(episode = TrackingEpisode(season = 2, number = 7)), WATCHED_AT)
            ),
            allowRewatch = true
        )

        // The one call that is allowed to open a session, with the episode the user watched.
        assertEquals(listOf("/sync/history"), engine.paths)
        assertTrue("allow_rewatch=yes" in engine.urls.single())
        assertTrue("\"is_rewatch\":true" in engine.bodies.single())
        // The episode is in the history already, so the account answers not_found and holds the session
        // that Simkl opened behind it. The write is not a failure for that.
        assertEquals(1, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
        assertFalse(result.isComplete)
    }

    @Test
    fun `the same conflict is a failure when no rewatch was asked for`() = runBlocking {
        val engine = RecordingEngine(response(409, NOT_FOUND_CONFLICT))
        val service = SimklMutationService(client(engine))

        assertThrows(SimklApiException::class.java) {
            runBlocking {
                service.addToHistory(
                    listOf(
                        TrackingHistoryItem(
                            show(episode = TrackingEpisode(season = 2, number = 7)),
                            WATCHED_AT
                        )
                    )
                )
            }
        }
        assertFalse("allow_rewatch" in engine.urls.single())
    }

    @Test
    fun `only the rewatch scrobble carries the flag and only a stop is lenient about a conflict`() =
        runBlocking {
            val engine = RecordingEngine(
                response(201, """{"id":42,"action":"scrobble","progress":95}"""),
                response(201, """{"id":42,"action":"pause","progress":45}""")
            )
            val service = SimklMutationService(client(engine))

            service.scrobble(
                TrackingScrobbleAction.STOP,
                TrackingScrobbleEvent(movie(), 95.0),
                recordRewatch = true
            )
            service.scrobble(TrackingScrobbleAction.PAUSE, TrackingScrobbleEvent(movie(), 45.0))

            assertTrue("allow_rewatch=yes" in engine.urls[0])
            // A pause never asks to record anything, so the flag cannot reach the account there.
            assertFalse("allow_rewatch" in engine.urls[1])

            val conflictEngine = RecordingEngine(response(409, """{"watched_at":"2026-05-14T23:46:29Z"}"""))
            val conflict = SimklMutationService(client(conflictEngine))
            val stopResult = conflict.scrobble(
                TrackingScrobbleAction.STOP,
                TrackingScrobbleEvent(movie(), 95.0),
                recordRewatch = true
            )

            assertEquals(SimklScrobbleOutcome.SCROBBLE, stopResult.outcome)

            // The leniency stays tied to the action, not to the rewatch flag: a pause that conflicts
            // is still a pause the account refused.
            val pauseEngine = RecordingEngine(response(409, """{"watched_at":"2026-05-14T23:46:29Z"}"""))
            val pause = SimklMutationService(client(pauseEngine))
            val refusal = assertThrows(SimklApiException::class.java) {
                runBlocking {
                    pause.scrobble(
                        TrackingScrobbleAction.PAUSE,
                        TrackingScrobbleEvent(movie(), 45.0),
                        recordRewatch = true
                    )
                }
            }
            assertEquals(409, refusal.status)
        }

    @Test
    fun `a stop the account did not label is read against the threshold the user set`() = runBlocking {
        val engine = RecordingEngine(
            response(201, """{"id":7,"progress":85}"""),
            response(201, """{"id":7,"progress":85}"""),
            response(201, """{"id":7,"progress":85}""")
        )
        val service = SimklMutationService(client(engine))

        val aboveTheBar = service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 85.0),
            completionThresholdPercent = 90.0
        )
        val underTheBar = service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 85.0),
            completionThresholdPercent = 80.0
        )
        val byDefault = service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 85.0)
        )

        // 85 percent is a finished playback for the user who asked for 80 and not for the one who
        // asked for 90, which is the whole reason the threshold travels with the call.
        assertEquals(SimklScrobbleOutcome.PAUSE, aboveTheBar.outcome)
        assertEquals(SimklScrobbleOutcome.SCROBBLE, underTheBar.outcome)
        // Without the parameter the account's own bar decides, which is what every other caller had.
        assertEquals(SIMKL_REWATCH_MIN_PROGRESS_PERCENT, 80.0, 0.0)
        assertEquals(SimklScrobbleOutcome.SCROBBLE, byDefault.outcome)
    }

    @Test
    fun `the scrobble result carries the session the account opened`() {
        val result = SimklApiResponse(
            status = 201,
            body = """{"id":42,"action":"scrobble","progress":95,"rewatch_id":991,"rewatch_status":"active"}""",
            headers = emptyMap()
        ).toSimklScrobbleResult(
            requestedAction = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(movie(), 95.0),
            json = Json
        )

        assertEquals(991L, result.rewatchId)
        assertEquals(SimklRewatchStatus.ACTIVE, result.rewatchStatus)
        assertEquals(SimklScrobbleOutcome.SCROBBLE, result.outcome)
    }

    @Test
    fun `a rewatch status the client does not know is kept, a missing one is null`() {
        fun statusOf(body: String): SimklRewatchStatus? = SimklApiResponse(
            status = 201,
            body = body,
            headers = emptyMap()
        ).toSimklScrobbleResult(
            requestedAction = TrackingScrobbleAction.STOP,
            event = TrackingScrobbleEvent(movie(), 95.0),
            json = Json
        ).rewatchStatus

        assertEquals(SimklRewatchStatus.ACTIVE, statusOf("""{"rewatch_status":" ACTIVE "}"""))
        assertEquals(SimklRewatchStatus.CLOSED, statusOf("""{"rewatch_status":"closed"}"""))
        assertEquals(SimklRewatchStatus.COMPLETED, statusOf("""{"rewatch_status":"completed"}"""))
        assertEquals(SimklRewatchStatus.FIRST_WATCH, statusOf("""{"rewatch_status":"first_watch"}"""))
        assertEquals(SimklRewatchStatus.TOO_SOON, statusOf("""{"rewatch_status":"too_soon"}"""))
        assertEquals(SimklRewatchStatus.PRO_REQUIRED, statusOf("""{"rewatch_status":"pro_required"}"""))
        // A value Simkl adds later is still a session that was opened, so it is kept as unknown.
        assertEquals(SimklRewatchStatus.UNKNOWN, statusOf("""{"rewatch_status":"something_new"}"""))
        // No status and no session: the fields stay empty.
        assertNull(statusOf("""{"rewatch_status":""}"""))
        assertNull(statusOf("""{"id":42,"action":"scrobble"}"""))
        assertNull(
            SimklApiResponse(201, """{"id":42}""", emptyMap())
                .toSimklScrobbleResult(
                    TrackingScrobbleAction.STOP,
                    TrackingScrobbleEvent(movie(), 95.0),
                    Json
                ).rewatchId
        )
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

    private fun String.asObject() = Json.parseToJsonElement(this).jsonObject

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Terminator 3: Rise of the Machines",
        year = 2003,
        ids = TrackingExternalIds(simkl = 53536, imdb = "tt0181852", tmdb = 296)
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(simkl = 39687, imdb = "tt5753856"),
        episode = episode
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

    private companion object {
        const val WATCHED_AT = 1_700_000_000_000L

        /** The shape Simkl answers a repeat viewing with: the episode is not new, so it is not_found. */
        const val NOT_FOUND_CONFLICT =
            """{"not_found":{"movies":[],"shows":[{"title":"Dark","ids":{"imdb":"tt5753856"}}],"episodes":[]}}"""

        val configuration = SimklApiConfiguration("client-id", "nuvio", "1.0")
        fun response(status: Int, body: String = "{}") = SimklRawHttpResponse(status, body)
    }
}
