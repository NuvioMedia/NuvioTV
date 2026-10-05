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
        assertFalse("imdb" in show["ids"]!!.jsonObject)
        assertEquals("watched", show["status"]!!.jsonPrimitive.content)
        val season = show["seasons"]!!.jsonArray.single().jsonObject
        assertEquals(1, season["number"]!!.jsonPrimitive.int)
        val item = season["episodes"]!!.jsonArray.single().jsonObject
        assertEquals(2, item["number"]!!.jsonPrimitive.int)
        assertEquals("2026-09-26T08:00:00Z", item["tracked_at"]!!.jsonPrimitive.content)
        assertNull(body["movies"])
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
