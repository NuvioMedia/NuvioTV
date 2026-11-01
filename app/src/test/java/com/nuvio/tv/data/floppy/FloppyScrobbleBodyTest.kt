package com.nuvio.tv.data.floppy

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FloppyScrobbleBodyTest {
    @Test
    fun `a stop at or above eighty percent is completed`() {
        val body = body(TrackingScrobbleAction.STOP, movie(), 80.0)!!

        assertEquals("stop", body.text("action"))
        assertEquals("movie", body.text("media_type"))
        assertTrue(body["completed"]!!.jsonPrimitive.boolean)
    }

    @Test
    fun `a stop below eighty percent is not completed and carries no position`() {
        val body = body(TrackingScrobbleAction.STOP, movie(), 79.9)!!

        assertFalse(body["completed"]!!.jsonPrimitive.boolean)
        assertNull(body["position_seconds"])
        assertNull(body["duration_seconds"])
    }

    @Test
    fun `a stop under one percent is a skim and is not sent`() {
        assertNull(body(TrackingScrobbleAction.STOP, movie(), 0.99))
        assertEquals("stop", body(TrackingScrobbleAction.STOP, movie(), 1.0)!!.text("action"))
    }

    @Test
    fun `start and pause at the very beginning are still sent`() {
        assertEquals("start", body(TrackingScrobbleAction.START, movie(), 0.0)!!.text("action"))
        assertEquals("pause", body(TrackingScrobbleAction.PAUSE, movie(), 0.2)!!.text("action"))
    }

    @Test
    fun `start and pause never claim completion`() {
        for (action in listOf(TrackingScrobbleAction.START, TrackingScrobbleAction.PAUSE)) {
            val body = body(action, movie(), 95.0)!!

            assertEquals(action.wireValue, body.text("action"))
            assertNull(body["completed"])
        }
    }

    @Test
    fun `ids are sent as strings and only when valid`() {
        val body = body(
            TrackingScrobbleAction.START,
            movie(TrackingExternalIds(imdb = "tt0133093", tmdb = 603L, tvdb = "169")),
            0.0
        )!!

        val ids = body["ids"]!!.jsonObject
        assertEquals(JsonPrimitive("tt0133093"), ids["imdb"])
        assertEquals(JsonPrimitive("603"), ids["tmdb"])
        assertEquals(JsonPrimitive("169"), ids["tvdb"])
    }

    @Test
    fun `an episode carries its show title episode title and coordinates`() {
        val body = body(
            TrackingScrobbleAction.STOP,
            show(TrackingEpisode(season = 2, number = 5, title = "Pilot")),
            92.0
        )!!

        assertEquals("episode", body.text("media_type"))
        assertEquals("Severance", body.text("series_title"))
        assertEquals("Pilot", body.text("title"))
        assertEquals(2, body["season_number"]!!.jsonPrimitive.int)
        assertEquals(5, body["episode_number"]!!.jsonPrimitive.int)
    }

    @Test
    fun `a specials season zero is a real season`() {
        val body = body(TrackingScrobbleAction.START, show(TrackingEpisode(season = 0, number = 1)), 0.0)

        assertEquals(0, body!!["season_number"]!!.jsonPrimitive.int)
    }

    @Test
    fun `anime episodes may add the anidb id`() {
        val media = show(
            TrackingEpisode(season = 1, number = 3),
            kind = TrackingMediaKind.ANIME,
            ids = TrackingExternalIds(tmdb = 37854L, anidb = 69L)
        )

        val ids = body(TrackingScrobbleAction.START, media, 0.0)!!["ids"]!!.jsonObject

        assertEquals(JsonPrimitive("69"), ids["anidb"])
        assertEquals(JsonPrimitive("37854"), ids["tmdb"])
    }

    @Test
    fun `events Floppy could only guess at are skipped`() {
        val noIds = movie(TrackingExternalIds(mal = 1L, kitsu = 2L))
        val badImdb = movie(TrackingExternalIds(imdb = "nm0000206"))
        val showWithoutEpisode = show(episode = null)
        val tvdbOrdered = show(TrackingEpisode(season = 1, number = 1, usesTvdbSeasonMapping = true))
        val noSeason = show(TrackingEpisode(season = null, number = 1))
        val episodeZero = show(TrackingEpisode(season = 1, number = 0))

        for (media in listOf(noIds, badImdb, showWithoutEpisode, tvdbOrdered, noSeason, episodeZero)) {
            assertNull(body(TrackingScrobbleAction.STOP, media, 90.0))
        }
        assertNull(body(TrackingScrobbleAction.STOP, movie(), Double.NaN))
    }

    private fun body(action: TrackingScrobbleAction, media: TrackingMediaReference, percent: Double): JsonObject? =
        floppyScrobbleBody(action, TrackingScrobbleEvent(media, percent))

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    private fun movie(ids: TrackingExternalIds = TrackingExternalIds(tmdb = 603L)) = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "The Matrix",
        ids = ids
    )

    private fun show(
        episode: TrackingEpisode?,
        kind: TrackingMediaKind = TrackingMediaKind.SHOW,
        ids: TrackingExternalIds = TrackingExternalIds(imdb = "tt11280740")
    ) = TrackingMediaReference(kind = kind, title = "Severance", ids = ids, episode = episode)
}
