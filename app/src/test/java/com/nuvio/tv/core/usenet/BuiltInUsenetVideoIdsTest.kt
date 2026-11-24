package com.nuvio.tv.core.usenet

import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BuiltInUsenetVideoIdsTest {
    @Test fun `only an anime catalog's ids are looked up in their meta`() {
        assertEquals("kitsu:46474", animeMetaId("kitsu:46474:5", series = true))
        assertEquals("mal:52991", animeMetaId("mal:52991:12", series = true))
        assertEquals("kitsu:1376", animeMetaId("kitsu:1376", series = false))
        assertNull(animeMetaId("tt22248376:1:5", series = true))
        assertNull(animeMetaId("tmdb:209867:1:5", series = true))
        assertNull(animeMetaId("tt0111161", series = false))
        assertNull(animeMetaId("kitsu:46474", series = true))
        assertNull(animeMetaId("custom:46474:5", series = true))
    }

    @Test fun `an episode takes the IMDb numbering its meta gives, never the catalog's own`() {
        val meta = meta("tt22248376",
            episode("kitsu:49240:1", imdbSeason = 2, imdbEpisode = 1),
            episode("kitsu:49240:2", imdbId = "tt999", imdbSeason = 1, imdbEpisode = 2),
            episode("kitsu:49240:3", imdbSeason = null, imdbEpisode = 3),
            episode("kitsu:49240:4", imdbSeason = 2, imdbEpisode = null))
        assertEquals(ImdbEquivalent("tt22248376", 2, 1), imdbEquivalent(meta, "kitsu:49240:1", series = true))
        // An episode's own IMDb id goes before the show's.
        assertEquals(ImdbEquivalent("tt999", 1, 2), imdbEquivalent(meta, "kitsu:49240:2", series = true))
        // Without both numbers the episode is not searched: the catalog's would name another one.
        assertNull(imdbEquivalent(meta, "kitsu:49240:3", series = true))
        assertNull(imdbEquivalent(meta, "kitsu:49240:4", series = true))
        assertNull(imdbEquivalent(meta, "kitsu:49240:9", series = true))
        assertNull(imdbEquivalent(meta(null, episode("kitsu:1:1", imdbSeason = 1, imdbEpisode = 1)), "kitsu:1:1", series = true))
    }

    @Test fun `a film takes its meta's IMDb id`() {
        assertEquals(ImdbEquivalent("tt0245429", null, null), imdbEquivalent(meta("tt0245429"), "kitsu:1376", series = false))
        assertNull(imdbEquivalent(meta(null), "kitsu:1376", series = false))
    }

    @Test fun `the engine picks a pack's file by the numbering the release was searched by`() {
        val plain = Stream(name = null, title = null, description = null, url = null, ytId = null, infoHash = null,
            fileIdx = null, externalUrl = null, behaviorHints = null, addonName = "Built-in Usenet", addonLogo = null)
        assertEquals(1 to 5, engineEpisode(plain, 1, 5))
        assertEquals(null to null, engineEpisode(plain, null, null))
        assertEquals(2 to 1, engineEpisode(plain.copy(usenetSeason = 2, usenetEpisode = 1), 1, 1))
    }

    private fun meta(imdbId: String?, vararg videos: Video): Meta = mockk {
        every { this@mockk.imdbId } returns imdbId
        every { this@mockk.videos } returns videos.toList()
    }

    private fun episode(id: String, imdbId: String? = null, imdbSeason: Int?, imdbEpisode: Int?) = Video(id = id,
        title = id, released = null, thumbnail = null, season = 1, episode = id.substringAfterLast(':').toInt(),
        overview = null, imdbId = imdbId, imdbSeason = imdbSeason, imdbEpisode = imdbEpisode)
}
