package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * How a rewatch removal finds its session and what it sends.
 *
 * The lookup uses the reference the user marked, so a show keeps its exact season and episode. An
 * anime may carry no season at all, because Simkl lists anime episodes flat. The request keeps the
 * anime-native reference and Simkl's history shape: the item stays under `shows`.
 */
class SimklRewatchRemovalTest {

    @Test
    fun `an anime reference without a season finds the running session that lists the episode`() {
        val sessions = listOf(
            animeSession(id = 70, seasons = listOf(flatSeason(7)), lastWatchedAt = OLD),
            animeSession(id = 71, seasons = listOf(flatSeason(7)), lastWatchedAt = NEWER),
        )

        val rewatchId = sessions.activeRewatchSessionIdForEpisode(
            media = animeReference(),
            seasonNumber = null,
            episodeNumber = 7,
        )

        // Both sessions hold episode 7, and the most recently watched running session wins.
        assertEquals(71L, rewatchId)
    }

    @Test
    fun `an anime with a known season falls back to a flat session`() {
        val sessions = listOf(animeSession(id = 72, seasons = listOf(flatSeason(7))))

        val rewatchId = sessions.activeRewatchSessionIdForRemoval(
            media = animeReference(season = 2, number = 7),
            seasonNumber = 2,
            episodeNumber = 7,
        )

        assertEquals(72L, rewatchId)
    }

    @Test
    fun `an anime with a tvdb season matches the absolute episode on a flat session`() {
        val sessions = listOf(animeSession(id = 73, seasons = listOf(flatSeason(7))))

        val rewatchId = sessions.activeRewatchSessionIdForRemoval(
            media = animeReference(season = 2, number = 20),
            seasonNumber = 2,
            episodeNumber = 20,
            animeEpisodeNumber = 7,
        )

        assertEquals(73L, rewatchId)
    }

    @Test
    fun `a show without a season cannot match an arbitrary stored season`() {
        val sessions = listOf(showSession(id = 81, seasons = listOf(season(3, 7))))

        val rewatchId = sessions.activeRewatchSessionIdForRemoval(
            media = showReference(season = null, number = 7),
            seasonNumber = null,
            episodeNumber = 7,
        )

        assertNull(rewatchId)
    }

    @Test
    fun `a show reference still requires the exact season and episode`() {
        val sessions = listOf(showSession(id = 80, seasons = listOf(season(2, 6, 7))))

        assertEquals(
            80L,
            sessions.activeRewatchSessionIdForEpisode(
                media = showReference(season = 2, number = 7),
                seasonNumber = 2,
                episodeNumber = 7,
            ),
        )
        assertNull(
            sessions.activeRewatchSessionIdForEpisode(
                media = showReference(season = 2, number = 8),
                seasonNumber = 2,
                episodeNumber = 8,
            ),
        )
        assertNull(
            sessions.activeRewatchSessionIdForEpisode(
                media = showReference(season = 3, number = 7),
                seasonNumber = 3,
                episodeNumber = 7,
            ),
        )
    }

    @Test
    fun `an episode in another known season is not matched when the target season is known`() {
        val sessions = listOf(showSession(id = 90, seasons = listOf(season(3, 7))))

        val rewatchId = sessions.activeRewatchSessionIdForEpisode(
            media = showReference(season = 2, number = 7),
            seasonNumber = 2,
            episodeNumber = 7,
        )

        assertNull(rewatchId)
    }

    @Test
    fun `an anime removal body still uses shows and carries the session and episode`() {
        val body = buildSimklRewatchRemovalBody(listOf(animeReference()), REWATCH_ID)
            .let { json -> Json.parseToJsonElement(json).jsonObject }

        assertNull(body["movies"])
        assertNull(body["anime"])
        val show = body.getValue("shows").jsonArray.single().jsonObject
        assertEquals(REWATCH_ID.toString(), show.getValue("rewatch_id").jsonPrimitive.content)
        val ids = show.getValue("ids").jsonObject
        assertEquals("42203", ids.getValue("mal").jsonPrimitive.content)
        assertEquals("154587", ids.getValue("anilist").jsonPrimitive.content)
        // Flat anime numbering has no season, and the episode stays in episodes[], not seasons[].
        assertNull(show["seasons"])
        val episode = show.getValue("episodes").jsonArray.single().jsonObject
        assertEquals(7, episode.getValue("number").jsonPrimitive.content.toInt())
        assertNull(episode["season"])
    }

    private fun animeReference(
        season: Int? = null,
        number: Int = 7,
    ) = TrackingMediaReference(
        kind = TrackingMediaKind.ANIME,
        title = "Frieren",
        year = 2023,
        ids = TrackingExternalIds(mal = 42203, anilist = 154587),
        episode = TrackingEpisode(season = season, number = number),
    )

    private fun showReference(
        season: Int?,
        number: Int,
    ) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        title = "Dark",
        year = 2017,
        ids = TrackingExternalIds(imdb = "tt5753856"),
        episode = TrackingEpisode(season = season, number = number),
    )

    private fun animeSession(
        id: Long,
        seasons: List<SimklSeason>,
        lastWatchedAt: String = OLD,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.ANIME,
        lastWatchedAt = lastWatchedAt,
        show = SimklMedia(
            title = "Frieren",
            year = 2023,
            ids = animeIds(),
        ),
        seasons = seasons,
        isRewatch = true,
        rewatchId = id,
        rewatchStatus = "active",
    )

    private fun showSession(
        id: Long,
        seasons: List<SimklSeason>,
    ): SimklLibraryEntry = SimklLibraryEntry(
        mediaType = SimklMediaType.SHOWS,
        lastWatchedAt = OLD,
        show = SimklMedia(
            title = "Dark",
            year = 2017,
            ids = showIds(),
        ),
        seasons = seasons,
        isRewatch = true,
        rewatchId = id,
        rewatchStatus = "active",
    )

    private fun flatSeason(number: Int): SimklSeason = SimklSeason(
        number = null,
        episodes = listOf(SimklEpisode(number = number)),
    )

    private fun season(number: Int, vararg episodes: Int): SimklSeason = SimklSeason(
        number = number,
        episodes = episodes.map { episode -> SimklEpisode(number = episode) },
    )

    private fun animeIds(): Map<String, JsonElement> = mapOf(
        "mal" to JsonPrimitive("42203"),
        "anilist" to JsonPrimitive("154587"),
    )

    private fun showIds(): Map<String, JsonElement> = mapOf(
        "imdb" to JsonPrimitive("tt5753856"),
    )

    private companion object {
        const val REWATCH_ID = 8975L
        const val OLD = "2026-03-01T20:00:00Z"
        const val NEWER = "2026-08-01T20:00:00Z"
    }
}
