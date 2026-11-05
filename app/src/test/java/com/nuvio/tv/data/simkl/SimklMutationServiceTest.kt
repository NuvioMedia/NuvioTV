package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
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

class SimklMutationServiceTest {
    private val json = Json

    @Test
    fun `list mutation batches types and puts destination on every item`() {
        val body = buildSimklListMutationBody(
            listOf(
                movie().copy(posterUrl = "https://catalog.example/poster.webp"),
                anime()
            ),
            TrackingListStatus.PLAN_TO_WATCH
        ).asObject()

        assertNull(body["to"])
        val movie = body.getValue("movies").jsonArray.single().jsonObject
        val anime = body.getValue("shows").jsonArray.single().jsonObject
        assertEquals("plantowatch", movie.getValue("to").jsonPrimitive.content)
        assertEquals("plantowatch", anime.getValue("to").jsonPrimitive.content)
        assertEquals(53536L, movie.getValue("ids").jsonObject.getValue("simkl").jsonPrimitive.content.toLong())
        assertEquals("tt0181852", movie.getValue("ids").jsonObject.getValue("imdb").jsonPrimitive.content)
        assertNull(movie.getValue("ids").jsonObject["trakt"])
        assertNull(movie["poster"])
        assertNull(movie["posterUrl"])
        assertEquals(16498L, anime.getValue("ids").jsonObject.getValue("mal").jsonPrimitive.content.toLong())
    }

    @Test
    fun `history mutation groups show episodes and keeps timestamps at event granularity`() {
        val first = show(TrackingEpisode(season = 1, number = 1))
        val second = first.copy(episode = TrackingEpisode(season = 1, number = 2))
        val body = buildSimklHistoryMutationBody(
            listOf(
                TrackingHistoryItem(first, 1_700_000_000_000L),
                TrackingHistoryItem(second, 1_700_003_600_000L),
                TrackingHistoryItem(movie(), 1_700_000_000_000L)
            )
        ).asObject()

        val showItem = body.getValue("shows").jsonArray.single().jsonObject
        val episodes = showItem.getValue("seasons").jsonArray.single().jsonObject
            .getValue("episodes").jsonArray
        assertEquals(2, episodes.size)
        assertNull(episodes[0].jsonObject["season"])
        assertEquals("2023-11-14T22:13:20Z", episodes[0].jsonObject.getValue("watched_at").jsonPrimitive.content)
        assertEquals("2023-11-14T23:13:20Z", episodes[1].jsonObject.getValue("watched_at").jsonPrimitive.content)
        assertEquals(
            "2023-11-14T22:13:20Z",
            body.getValue("movies").jsonArray.single().jsonObject
                .getValue("watched_at").jsonPrimitive.content
        )
    }

    @Test
    fun `anime history uses tvdb mapping flag only for seasonal coordinates`() {
        val seasonal = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(anime(TrackingEpisode(2, 4)), 1_700_000_000_000L))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertTrue(seasonal.getValue("use_tvdb_anime_seasons").jsonPrimitive.content.toBoolean())

        val flat = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(anime(TrackingEpisode(number = 4)), 1_700_000_000_000L))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertNull(flat["use_tvdb_anime_seasons"])

        val regularShow = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(show(TrackingEpisode(2, 4)), 1_700_000_000_000L))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertNull(regularShow["use_tvdb_anime_seasons"])
    }

    @Test
    fun `anime removal uses shows and excludes watch fields`() {
        val item = buildSimklHistoryRemovalBody(
            listOf(anime(TrackingEpisode(number = 4)))
        ).asObject().getValue("shows").jsonArray.single().jsonObject

        assertNull(item["watched_at"])
        assertNull(item["status"])
        assertEquals(4, item.getValue("episodes").jsonArray.single().jsonObject
            .getValue("number").jsonPrimitive.content.toInt())

        val seasonal = buildSimklHistoryRemovalBody(
            listOf(anime(TrackingEpisode(2, 4)))
        ).asObject().getValue("shows").jsonArray.single().jsonObject
        assertTrue(seasonal.getValue("use_tvdb_anime_seasons").jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `scrobble payload clamps progress and uses type specific wrappers`() {
        val movieBody = buildSimklScrobbleBody(
            TrackingScrobbleEvent(movie(), 105.129)
        ).asObject()
        assertEquals(100.0, movieBody.getValue("progress").jsonPrimitive.content.toDouble(), 0.0)
        assertTrue("movie" in movieBody)
        assertFalse("show" in movieBody)

        val earlyPauseBody = buildSimklScrobbleBody(
            TrackingScrobbleEvent(movie(), 0.42984203)
        ).asObject()
        assertEquals(0.43, earlyPauseBody.getValue("progress").jsonPrimitive.content.toDouble(), 0.0)

        val seasonalAnime = buildSimklScrobbleBody(
            TrackingScrobbleEvent(anime(TrackingEpisode(2, 4)), 42.236)
        ).asObject()
        assertEquals(42.24, seasonalAnime.getValue("progress").jsonPrimitive.content.toDouble(), 0.0)
        assertTrue("show" in seasonalAnime)
        assertFalse("anime" in seasonalAnime)
        assertEquals(2, seasonalAnime.getValue("episode").jsonObject
            .getValue("season").jsonPrimitive.content.toInt())

        val flatAnime = buildSimklScrobbleBody(
            TrackingScrobbleEvent(anime(TrackingEpisode(number = 4)), 42.236)
        ).asObject()
        assertTrue("anime" in flatAnime)
        assertFalse("show" in flatAnime)
        assertNull(flatAnime.getValue("episode").jsonObject["season"])
    }

    @Test
    fun `service reports partial not found and duplicate stop as scrobbled`() = runBlocking {
        val engine = RecordingEngine(
            response(201, """{"added":{"movies":[{"to":"completed"}]},"not_found":{"movies":[{"title":"Missing"}],"shows":[]}}"""),
            response(409, """{"watched_at":"2026-05-14T23:46:29Z","expires_at":"2026-05-15T00:46:29Z"}""")
        )
        var now = 0L
        var committed = 0
        val client = SimklApiClient(
            engine = engine,
            configuration = configuration,
            authorization = { testSimklAuthorization() },
            onUnauthorized = {},
            nowEpochMs = { now },
            sleep = { duration -> now += duration },
            retryJitterMs = { 0L }
        )
        val testedService = SimklMutationService(client) { committed += 1 }

        val result = testedService.moveToList(
            listOf(movie(), movie().copy(title = "Missing", ids = TrackingExternalIds())),
            TrackingListStatus.PLAN_TO_WATCH
        )
        val scrobbleResult = testedService.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 90.0)
        )

        assertEquals(2, result.attemptedCount)
        assertEquals(1, result.notFoundCount)
        assertEquals(listOf(TrackingListStatus.COMPLETED), result.resolvedListStatuses)
        assertFalse(result.isComplete)
        assertEquals(listOf("/sync/add-to-list", "/scrobble/stop"), engine.paths)
        assertEquals(SimklScrobbleOutcome.SCROBBLE, scrobbleResult.outcome)
        assertEquals("2026-05-14T23:46:29Z", scrobbleResult.watchedAt)
        assertEquals(1, committed)
    }

    @Test
    fun `history response exposes status media kind and anime subtype`() = runBlocking {
        val engine = RecordingEngine(
            response(
                201,
                """{"added":{"statuses":[{"response":{"status":"watching","simkl_type":"anime","anime_type":"tv"}}]},"not_found":{"movies":[],"shows":[],"episodes":[]}}"""
            )
        )
        val result = SimklMutationService(client(engine)).addToHistory(
            listOf(TrackingHistoryItem(anime(TrackingEpisode(number = 1)), 1_700_000_000_000L))
        )

        assertEquals(listOf(TrackingListStatus.WATCHING), result.resolvedListStatuses)
        assertEquals(TrackingMediaKind.ANIME, result.resolutions.single().mediaKind)
        assertEquals("tv", result.resolutions.single().providerSubtype)
    }

    @Test
    fun `failed scrobble is not replayed`() {
        val engine = RecordingEngine(response(503), response(200))
        val service = SimklMutationService(client(engine))

        assertThrows(SimklApiException::class.java) {
            runBlocking {
                service.scrobble(
                    TrackingScrobbleAction.PAUSE,
                    TrackingScrobbleEvent(movie(), 45.0)
                )
            }
        }
        assertEquals(listOf("/scrobble/pause"), engine.paths)
    }

    @Test
    fun `successful pause uses pause endpoint`() = runBlocking {
        val engine = RecordingEngine(response(201, """{"action":"pause","progress":45}"""))
        var committed = 0
        val service = SimklMutationService(client(engine)) { committed += 1 }

        val result = service.scrobble(
            TrackingScrobbleAction.PAUSE,
            TrackingScrobbleEvent(movie(), 45.0)
        )

        assertEquals(listOf("/scrobble/pause"), engine.paths)
        assertEquals(SimklScrobbleOutcome.PAUSE, result.outcome)
        assertEquals(45.0, result.progress, 0.0)
        assertEquals(0, committed)
    }

    @Test
    fun `low progress stop returns a paused playback without invalidating sync`() = runBlocking {
        val engine = RecordingEngine(
            response(
                201,
                """{"id":42,"action":"pause","progress":30,"movie":{"title":"Terminator 3: Rise of the Machines","year":2003,"ids":{"simkl":53536}}}"""
            )
        )
        var committed = 0
        val service = SimklMutationService(client(engine)) { committed += 1 }

        val result = service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 30.0)
        )

        assertEquals(listOf("/scrobble/stop"), engine.paths)
        assertEquals(SimklScrobbleOutcome.PAUSE, result.outcome)
        assertEquals(42L, result.playbackId)
        assertEquals(30.0, result.progress, 0.0)
        assertEquals(0, committed)
    }

    @Test
    fun `a scrobble tells Simkl where a stop becomes a watch`() = runBlocking {
        val engine = RecordingEngine(
            response(201, """{"action":"scrobble","progress":96}"""),
            response(201, """{"action":"pause","progress":90,"rewatch_id":77,"rewatch_status":"active"}""")
        )
        val service = SimklMutationService(client(engine))

        service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 96.0),
            completionThresholdPercent = 96.0
        )
        service.scrobble(
            TrackingScrobbleAction.STOP,
            TrackingScrobbleEvent(movie(), 90.0),
            recordRewatch = true,
            completionThresholdPercent = 90.0
        )

        assertEquals(listOf("/scrobble/stop", "/scrobble/stop"), engine.paths)
        assertTrue("min_progress=96" in engine.urls[0])
        assertFalse("allow_rewatch" in engine.urls[0])
        // The rewatch flag rides beside the threshold, and both are what the account reads.
        assertTrue("min_progress=90" in engine.urls[1])
        assertTrue("allow_rewatch=yes" in engine.urls[1])
    }

    @Test
    fun `invalid mutation identity fails before network access`() {
        val engine = RecordingEngine(response(200))
        val service = SimklMutationService(client(engine))

        assertThrows(IllegalArgumentException::class.java) {
            runBlocking {
                service.moveToList(
                    listOf(TrackingMediaReference(TrackingMediaKind.MOVIE)),
                    TrackingListStatus.COMPLETED
                )
            }
        }
        assertTrue(engine.paths.isEmpty())
    }

    private fun client(engine: RecordingEngine, now: () -> Long = { 0L }): SimklApiClient =
        SimklApiClient(
            engine = engine,
            configuration = configuration,
            authorization = { testSimklAuthorization() },
            onUnauthorized = {},
            nowEpochMs = now,
            sleep = {},
            retryJitterMs = { 0L }
        )

    private fun String.asObject() = json.parseToJsonElement(this).jsonObject

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Terminator 3: Rise of the Machines",
        year = 2003,
        ids = TrackingExternalIds(simkl = 53536, imdb = "tt0181852", tmdb = 296, trakt = 123)
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "The Walking Dead",
        year = 2010,
        ids = TrackingExternalIds(simkl = 2090, imdb = "tt1520211", tvdb = "153021"),
        episode = episode
    )

    private fun anime(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(simkl = 39687, mal = 16498, anidb = 9541),
        episode = episode
    )

    private class RecordingEngine(vararg responses: SimklRawHttpResponse) : SimklHttpEngine {
        private val queued = responses.toMutableList()
        val paths = mutableListOf<String>()
        val urls = mutableListOf<String>()

        override suspend fun execute(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: String
        ): SimklRawHttpResponse {
            urls += url
            paths += url.substringAfter("api.simkl.com").substringBefore('?')
            return queued.removeAt(0)
        }
    }

    private companion object {
        val configuration = SimklApiConfiguration("client-id", "nuvio", "1.0")
        fun response(status: Int, body: String = "{}") = SimklRawHttpResponse(status, body)
    }
    @Test
    fun `a mark of a repeat asks for a rewatch and the answer says how it was booked`() = runBlocking {
        val engine = RecordingEngine(
            response(
                201,
                """{"added":{"statuses":[{"response":{"status":"completed","rewatch_id":21284,"rewatch_status":"active"}}]},"not_found":{"movies":[],"shows":[],"episodes":[]}}"""
            )
        )
        val result = SimklMutationService(client(engine)).addToHistory(
            listOf(
                TrackingHistoryItem(show(TrackingEpisode(season = 2, number = 7)), 1_700_000_000_000L)
                    .copy(isRewatch = true, rewatchId = 21284L)
            )
        )

        // Without the query flag Simkl reads the very same write as a plain add and records it as
        // nothing, which is what left a marked episode unrecorded and the run untouched.
        assertTrue("allow_rewatch=yes" in engine.urls.single())
        // The body the flag rides on is asserted where the bodies are read back
        // (SimklRewatchWriteTest); what this call owes is the answer, which a caller reports to the user.
        assertEquals("active", result.resolutions.single().rewatchStatus)
    }


}
