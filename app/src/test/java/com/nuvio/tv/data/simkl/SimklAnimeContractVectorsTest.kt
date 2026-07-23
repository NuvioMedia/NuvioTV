package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingListStatus
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Golden contract vectors for Simkl anime writes, derived from the Simkl anime
 * guide (https://api.simkl.org/guides/anime) Path A / Path B. These pin the
 * envelope and payload shape the mutation builders must produce, so history,
 * list, and scrobble writes stay consistent with each other and with the guide.
 */
class SimklAnimeContractVectorsTest {

    // Path B (native): anime with anime-native ids + a flat/absolute episode
    // goes in the anime[] envelope with a flat episode and no season key.
    @Test
    fun `history native cour-split anime uses anime envelope with flat episode`() {
        val body = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(nativeAnime(TrackingEpisode(number = 4)), WATCHED_AT))
        ).obj()

        assertNull(body["shows"])
        val entry = body.getValue("anime").jsonArray.single().jsonObject
        assertNull(entry["use_tvdb_anime_seasons"])
        val episode = entry.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(4, episode.getValue("number").jsonPrimitive.content.toInt())
        assertNull(episode["season"])
        assertEquals(16498L, entry.ids().getValue("mal").jsonPrimitive.content.toLong())
    }

    // Path A (hybrid): anime with TMDB/TVDB identity and seasonal coordinates
    // goes in shows[] with use_tvdb_anime_seasons so Simkl cross-maps TVDB.
    @Test
    fun `history seasonal anime uses shows envelope with tvdb cross-mapping flag`() {
        val body = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(hybridAnime(TrackingEpisode(season = 3, number = 13)), WATCHED_AT))
        ).obj()

        assertNull(body["anime"])
        val entry = body.getValue("shows").jsonArray.single().jsonObject
        assertTrue(entry.getValue("use_tvdb_anime_seasons").jsonPrimitive.content.toBoolean())
        val season = entry.getValue("seasons").jsonArray.single().jsonObject
        assertEquals(3, season.getValue("number").jsonPrimitive.content.toInt())
        assertEquals(1429L, entry.ids().getValue("tmdb").jsonPrimitive.content.toLong())
    }

    @Test
    fun `history movie uses movies envelope`() {
        val body = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(movie(), WATCHED_AT))
        ).obj()

        assertNull(body["shows"])
        assertNull(body["anime"])
        assertEquals(1, body.getValue("movies").jsonArray.size)
    }

    @Test
    fun `history non-anime show uses shows envelope without flag`() {
        val body = buildSimklHistoryMutationBody(
            listOf(TrackingHistoryItem(show(TrackingEpisode(season = 2, number = 1)), WATCHED_AT))
        ).obj()

        val entry = body.getValue("shows").jsonArray.single().jsonObject
        assertNull(entry["use_tvdb_anime_seasons"])
    }

    @Test
    fun `list mutation routes anime to anime envelope`() {
        val body = buildSimklListMutationBody(
            listOf(movie(), nativeAnime(), show()),
            TrackingListStatus.PLAN_TO_WATCH
        ).obj()

        assertEquals(1, body.getValue("movies").jsonArray.size)
        assertEquals(1, body.getValue("anime").jsonArray.size)
        assertEquals(1, body.getValue("shows").jsonArray.size)
    }

    @Test
    fun `scrobble native anime uses anime wrapper without season`() {
        val body = buildSimklScrobbleBody(
            TrackingScrobbleEvent(nativeAnime(TrackingEpisode(number = 4)), 80.0)
        ).obj()

        assertTrue("anime" in body)
        assertNull(body["show"])
        assertNull(body.getValue("episode").jsonObject["season"])
    }

    @Test
    fun `scrobble seasonal anime uses show wrapper`() {
        val body = buildSimklScrobbleBody(
            TrackingScrobbleEvent(hybridAnime(TrackingEpisode(season = 3, number = 13)), 80.0)
        ).obj()

        assertTrue("show" in body)
        assertNull(body["anime"])
    }

    // -- fixtures --------------------------------------------------------

    private fun movie() = TrackingMediaReference(
        kind = TrackingMediaKind.MOVIE,
        title = "Inception",
        year = 2010,
        ids = TrackingExternalIds(simkl = 53536, imdb = "tt1375666", tmdb = 27205)
    )

    private fun show(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Breaking Bad",
        year = 2008,
        ids = TrackingExternalIds(simkl = 46639, imdb = "tt0903747", tvdb = "81189"),
        episode = episode
    )

    private fun nativeAnime(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(simkl = 39687, mal = 16498, anidb = 9541),
        episode = episode
    )

    private fun hybridAnime(episode: TrackingEpisode? = null) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Attack on Titan",
        year = 2013,
        ids = TrackingExternalIds(simkl = 39687, tmdb = 1429, tvdb = "267440"),
        episode = episode
    )

    private fun JsonObject.ids(): JsonObject = getValue("ids").jsonObject

    private fun String.obj(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private companion object {
        const val WATCHED_AT = 1_700_000_000_000L
    }
}
