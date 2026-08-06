package com.nuvio.tv.core.plugin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PluginEpisodeRequestRulesTest {

    @Test
    fun `absoluteAnimeEpisodeNumber reads kitsu absolute form`() {
        assertEquals(68, absoluteAnimeEpisodeNumber("kitsu:6448:68"))
        assertEquals(1, absoluteAnimeEpisodeNumber("kitsu:6448:1"))
    }

    @Test
    fun `absoluteAnimeEpisodeNumber reads mal anilist and anidb absolute form`() {
        assertEquals(68, absoluteAnimeEpisodeNumber("mal:63375:68"))
        assertEquals(12, absoluteAnimeEpisodeNumber("anilist:12345:12"))
        assertEquals(7, absoluteAnimeEpisodeNumber("anidb:9541:7"))
    }

    @Test
    fun `absoluteAnimeEpisodeNumber rejects content-only anime ids without episode`() {
        assertNull(absoluteAnimeEpisodeNumber("mal:63375"))
        assertNull(absoluteAnimeEpisodeNumber("kitsu:6448"))
    }

    @Test
    fun `absoluteAnimeEpisodeNumber rejects malformed anime ids`() {
        // Trackers never encode season; extra segments are not a valid S/E form.
        assertNull(absoluteAnimeEpisodeNumber("mal:63375:1:5"))
        assertNull(absoluteAnimeEpisodeNumber("kitsu:6448:2:10"))
        assertNull(absoluteAnimeEpisodeNumber("mal::12"))
        assertNull(absoluteAnimeEpisodeNumber("mal:abc:12"))
        assertNull(absoluteAnimeEpisodeNumber("mal:63375:ep12"))
    }

    @Test
    fun `absoluteAnimeEpisodeNumber ignores non-anime ids`() {
        assertNull(absoluteAnimeEpisodeNumber("tt2098220:2:10"))
        assertNull(absoluteAnimeEpisodeNumber("tt2098220"))
        assertNull(absoluteAnimeEpisodeNumber("tmdb:12345:2:10"))
        assertNull(absoluteAnimeEpisodeNumber("tmdb:12345"))
    }

    @Test
    fun `resolvePluginSeasonEpisode forces absolute for anime tracker video ids`() {
        assertEquals(
            null to 68,
            resolvePluginSeasonEpisode(
                videoId = "kitsu:6448:68",
                season = 2,
                episode = 10
            )
        )
        assertEquals(
            null to 68,
            resolvePluginSeasonEpisode(
                videoId = "mal:63375:68",
                season = 2,
                episode = 10
            )
        )
        assertEquals(
            null to 7,
            resolvePluginSeasonEpisode(
                videoId = "anidb:9541:7",
                season = 1,
                episode = 7
            )
        )
    }

    @Test
    fun `resolvePluginSeasonEpisode keeps SxE for non-anime or incomplete ids`() {
        assertEquals(
            2 to 10,
            resolvePluginSeasonEpisode(
                videoId = "tt2098220:2:10",
                season = 2,
                episode = 10
            )
        )
        // Content-only anime id (no episode segment) → fall back to UI S/E.
        assertEquals(
            2 to 10,
            resolvePluginSeasonEpisode(
                videoId = "kitsu:6448",
                season = 2,
                episode = 10
            )
        )
        // Malformed extra segments → not absolute; keep UI coordinates.
        assertEquals(
            1 to 5,
            resolvePluginSeasonEpisode(
                videoId = "mal:63375:1:5",
                season = 1,
                episode = 5
            )
        )
    }

    @Test
    fun `matchPluginEpisodeIndex exact SxE without episode-only fallback`() {
        // Flat absolute catalog (season null on every entry).
        val absoluteCatalog = listOf(
            null to 1,
            null to 10,
            null to 68
        )
        // S2E10 must NOT silently hit absolute episode 10.
        assertNull(matchPluginEpisodeIndex(absoluteCatalog, season = 2, episode = 10))
        // Absolute request for 68 hits the right row.
        assertEquals(2, matchPluginEpisodeIndex(absoluteCatalog, season = null, episode = 68))
        assertEquals(1, matchPluginEpisodeIndex(absoluteCatalog, season = null, episode = 10))
    }

    @Test
    fun `matchPluginEpisodeIndex exact multi-season catalog`() {
        val multiSeason = listOf(
            1 to 10,
            2 to 10,
            2 to 11
        )
        assertEquals(0, matchPluginEpisodeIndex(multiSeason, season = 1, episode = 10))
        assertEquals(1, matchPluginEpisodeIndex(multiSeason, season = 2, episode = 10))
        assertNull(matchPluginEpisodeIndex(multiSeason, season = 3, episode = 10))
    }

    @Test
    fun `matchPluginEpisodeIndex absolute request accepts season-1 tagged catalogs`() {
        val seasonOneTagged = listOf(
            1 to 1,
            1 to 10,
            1 to 68
        )
        assertEquals(2, matchPluginEpisodeIndex(seasonOneTagged, season = null, episode = 68))
        // Season-bearing request still requires exact S/E.
        assertEquals(1, matchPluginEpisodeIndex(seasonOneTagged, season = 1, episode = 10))
        assertNull(matchPluginEpisodeIndex(seasonOneTagged, season = 2, episode = 10))
    }

    @Test
    fun `end-to-end plugin request for kitsu absolute id`() {
        // Issue #2549: UI shows S2E10, videoId encodes absolute 68.
        val (pluginSeason, pluginEpisode) = resolvePluginSeasonEpisode(
            videoId = "kitsu:6448:68",
            season = 2,
            episode = 10
        )
        assertEquals(null to 68, pluginSeason to pluginEpisode)

        val absoluteCatalog = (1..70).map { null to it }
        assertEquals(
            67,
            matchPluginEpisodeIndex(absoluteCatalog, pluginSeason, pluginEpisode)
        )
        // Pre-fix behavior would have used S2/E10 and either wrong-matched or failed.
        assertNull(matchPluginEpisodeIndex(absoluteCatalog, season = 2, episode = 10))
    }
}

