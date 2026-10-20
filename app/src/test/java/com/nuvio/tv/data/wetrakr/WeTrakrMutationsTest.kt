package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WeTrakrMutationsTest {
    private val episode = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Breaking Bad",
        year = 2008,
        ids = TrackingExternalIds(imdb = "tt0903747", tmdb = 1396),
        episode = TrackingEpisode(season = 1, number = 2)
    )

    @Test
    fun `episodes are nested under their show by number`() {
        val target = episode.weTrakrTarget()!!
        val body = Json.parseToJsonElement(
            weTrakrTrackingBody(listOf(WeTrakrTrackingChange(target, "2026-09-26T08:00:00Z")), "watched")
        ).jsonObject

        val show = body["shows"]!!.jsonArray.single().jsonObject
        assertEquals(1396, show["ids"]!!.jsonObject["tmdb"]!!.jsonPrimitive.int)
        assertEquals("tt0903747", show["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertFalse("allow_rewatch" in body)
        assertEquals("watched", show["status"]!!.jsonPrimitive.content)
        val season = show["seasons"]!!.jsonArray.single().jsonObject
        assertEquals(1, season["number"]!!.jsonPrimitive.int)
        val item = season["episodes"]!!.jsonArray.single().jsonObject
        assertEquals(2, item["number"]!!.jsonPrimitive.int)
        assertEquals("2026-09-26T08:00:00Z", item["tracked_at"]!!.jsonPrimitive.content)
        assertNull(body["movies"])
    }

    @Test
    fun `allow rewatch is sent at the top of the body when given`() {
        val target = episode.weTrakrTarget()!!
        val body = Json.parseToJsonElement(
            weTrakrTrackingBody(listOf(WeTrakrTrackingChange(target, null)), "watched", allowRewatch = false)
        ).jsonObject

        assertEquals("false", body["allow_rewatch"]!!.jsonPrimitive.content)
    }

    @Test
    fun `errored items are split by the already watched prefix and not found counts nested episodes`() {
        val body = """{"added":{"movies":1},"errored":{""" +
            """"movies":[{"ids":{"tmdb":550},"status":"watched","error":"Already watched! Send allow_rewatch: true to add another play"}],""" +
            """"episodes":[{"show":{"ids":{"tmdb":1399}},"season":1,"number":3,"status":"watched","error":"Already watched!"},""" +
            """{"show":{"ids":{"tmdb":1399}},"season":1,"number":4,"status":"watched","error":"Invalid date"}]},""" +
            """"notFound":{"shows":[{"ids":{"tmdb":2},"seasons":[{"number":1,"episodes":[{"number":1},{"number":2}]}]}]}}"""

        assertEquals(WeTrakrErrored(alreadyWatched = 2, other = 1), weTrakrErrored(body))
        assertEquals(2, weTrakrNotFoundCount(body))
        assertEquals(WeTrakrErrored(), weTrakrErrored("{}"))
    }

    @Test
    fun `added and existing counts only use the batch's item types and skip the hold alias`() {
        val body = """{"added":{"movies":2,"shows":1,"episodes":10,"paused":{"episodes":3},"hold":{"episodes":3}},"existing":{"episodes":4}}"""

        assertEquals(15, weTrakrAddedCount(body, setOf("movies", "episodes")))
        assertEquals(3, weTrakrAddedCount(body, setOf("movies", "shows")))
        assertEquals(4, weTrakrExistingCount(body, setOf("movies", "episodes")))
        assertEquals(7, weTrakrAddedCount("""{"added":{"total":7}}""", setOf("movies")))
        assertNull(weTrakrAddedCount("{}", setOf("movies")))
    }

    @Test
    fun `not found keys cover movies, nested and flat episodes and whole seasons`() {
        val body = """{"notFound":{""" +
            """"movies":[{"ids":{"imdb":"tt1"}}],""" +
            """"shows":[{"ids":{"tmdb":2},"seasons":[{"number":1,"episodes":[{"number":3}]}]},{"ids":{"kitsu":12}}],""" +
            """"seasons":[{"show":{"ids":{"tvdb":5}},"season":2}],""" +
            """"episodes":[{"show":{"ids":{"tmdb":9}},"season":4,"number":1}]}}"""
        val keys = weTrakrNotFoundKeys(body)

        assertTrue(WeTrakrTarget(WeTrakrItemType.MOVIE, weTrakrMovie("tt1")).isIn(keys))
        assertTrue(WeTrakrTarget(WeTrakrItemType.EPISODE, WeTrakrMedia(WeTrakrIds(tmdb = 2)), 1, 3).isIn(keys))
        assertFalse(WeTrakrTarget(WeTrakrItemType.EPISODE, WeTrakrMedia(WeTrakrIds(tmdb = 2)), 1, 4).isIn(keys))
        assertTrue(WeTrakrTarget(WeTrakrItemType.SHOW, WeTrakrMedia(WeTrakrIds(kitsu = 12))).isIn(keys))
        assertTrue(WeTrakrTarget(WeTrakrItemType.EPISODE, WeTrakrMedia(WeTrakrIds(tvdb = 5)), 2, 7).isIn(keys))
        assertTrue(WeTrakrTarget(WeTrakrItemType.EPISODE, WeTrakrMedia(WeTrakrIds(imdb = "tt8", tmdb = 9)), 4, 1).isIn(keys))
    }

    @Test
    fun `anime ids are sent after the other ids and anime only episodes use the entry's first season`() {
        val anime = TrackingMediaReference(
            kind = TrackingMediaKind.ANIME,
            title = "Frieren",
            ids = TrackingExternalIds(kitsu = 46474, mal = 52991),
            episode = TrackingEpisode(season = null, number = 5)
        )
        val target = anime.weTrakrTarget()!!
        val ids = target.media.ids.requestIds()

        assertEquals(listOf("myanimelist", "kitsu"), ids.keys.toList())
        assertEquals(1, target.season)
        assertEquals(5, target.episode)
        assertNull(episode.copy(episode = TrackingEpisode(season = null, number = 5)).weTrakrTarget())
    }

    @Test
    fun `a play within a day of an existing one is not sent again`() {
        val target = episode.weTrakrTarget()!!
        val records = listOf(target.watched("2026-09-26T08:00:00Z"))

        assertTrue(WeTrakrTrackingChange(target, "2026-09-27T07:00:00Z").watchedRecently(records))
        assertFalse(WeTrakrTrackingChange(target, "2026-09-27T09:00:00Z").watchedRecently(records))
        assertFalse(WeTrakrTrackingChange(target.copy(episode = 3), "2026-09-26T08:00:00Z").watchedRecently(records))
    }

    @Test
    fun `scrobble body sends every id with the episode numbers`() {
        val body = episode.weTrakrTarget()!!.scrobbleBody(42.519, "1.0").toString()
        val json = Json.parseToJsonElement(body).jsonObject

        assertEquals("tt0903747", json["show"]!!.jsonObject["ids"]!!.jsonObject["imdb"]!!.jsonPrimitive.content)
        assertEquals(2, json["episode"]!!.jsonObject["number"]!!.jsonPrimitive.int)
        assertEquals("42.51", json["progress"]!!.jsonPrimitive.content)
        assertEquals("1.0", json["app_version"]!!.jsonPrimitive.content)
    }

    @Test
    fun `tvdb mapped episodes and references without ids are not sent`() {
        assertNull(episode.copy(episode = TrackingEpisode(1, 2, usesTvdbSeasonMapping = true)).weTrakrTarget())
        assertNull(episode.copy(ids = TrackingExternalIds(trakt = 1)).weTrakrTarget())
    }

    @Test
    fun `a movie reference becomes a movie target`() {
        val target = TrackingMediaReference(TrackingMediaKind.MOVIE, ids = TrackingExternalIds(imdb = "tt0468569")).weTrakrTarget()!!

        assertEquals(WeTrakrItemType.MOVIE, target.type)
        assertEquals("tt0468569", target.media.ids.contentId)
    }
}
