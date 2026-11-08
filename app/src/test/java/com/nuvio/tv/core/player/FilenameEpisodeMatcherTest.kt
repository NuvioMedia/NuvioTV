package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FilenameEpisodeMatcherTest {

    // --- buildNextFilename ---

    @Test
    fun `SxxExx pattern replaces episode number`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.Name.S01E03.1080p.WEB-DL.mkv", 1, 3, 1, 4
        )
        assertEquals("Show.Name.S01E04.1080p.WEB-DL.mkv", result)
    }

    @Test
    fun `SxxExx pattern replaces season and episode`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.Name.S01E12.1080p.mkv", 1, 12, 2, 1
        )
        assertEquals("Show.Name.S02E01.1080p.mkv", result)
    }

    @Test
    fun `SxEx without zero padding`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.S1E3.mkv", 1, 3, 1, 4
        )
        assertEquals("Show.S1E4.mkv", result)
    }

    @Test
    fun `lowercase sxxexx`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "show.s01e03.web.mkv", 1, 3, 1, 4
        )
        assertEquals("show.s01e04.web.mkv", result)
    }

    @Test
    fun `1x03 pattern`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.1x03.DVDRip.mkv", 1, 3, 1, 4
        )
        assertEquals("Show.1x04.DVDRip.mkv", result)
    }

    @Test
    fun `01x03 pattern with zero padding`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.01x03.DVDRip.mkv", 1, 3, 1, 4
        )
        assertEquals("Show.01x04.DVDRip.mkv", result)
    }

    @Test
    fun `EP03 pattern`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Anime.EP03.720p.mkv", null, 3, null, 4
        )
        assertEquals("Anime.EP04.720p.mkv", result)
    }

    @Test
    fun `EP dot 03 pattern`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Anime.EP.03.720p.mkv", null, 3, null, 4
        )
        assertEquals("Anime.EP.04.720p.mkv", result)
    }

    @Test
    fun `E03 pattern`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.E03.mkv", null, 3, null, 4
        )
        assertEquals("Show.E04.mkv", result)
    }

    @Test
    fun `movie filename returns null`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "The.Matrix.1999.2160p.UHD.BluRay.mkv", null, 0, null, 0
        )
        assertNull(result)
    }

    @Test
    fun `1920x1080 is not matched as season x episode`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Movie.1920x1080.BluRay.mkv", 1920, 1080, 1920, 1081
        )
        assertNull(result)
    }

    @Test
    fun `zero padding preserved`() {
        val result = FilenameEpisodeMatcher.buildNextFilename(
            "Show.S01E03.mkv", 1, 3, 1, 10
        )
        assertEquals("Show.S01E10.mkv", result)
    }

    // --- extractSeasonEpisode ---

    @Test
    fun `extract SxxExx`() {
        val result = FilenameEpisodeMatcher.extractSeasonEpisode("Show.S02E05.1080p.mkv")
        assertEquals(2 to 5, result)
    }

    @Test
    fun `extract 1x03`() {
        val result = FilenameEpisodeMatcher.extractSeasonEpisode("Show.1x03.DVDRip.mkv")
        assertEquals(1 to 3, result)
    }

    @Test
    fun `extract EP dot 03`() {
        val result = FilenameEpisodeMatcher.extractSeasonEpisode("Anime.EP.03.720p.mkv")
        assertEquals(null to 3, result)
    }

    @Test
    fun `extract EP03`() {
        val result = FilenameEpisodeMatcher.extractSeasonEpisode("Anime.EP03.720p.mkv")
        assertEquals(null to 3, result)
    }

    @Test
    fun `extract returns null for movie`() {
        assertNull(FilenameEpisodeMatcher.extractSeasonEpisode("The.Matrix.1999.mkv"))
    }

    @Test
    fun `extract does not match 1920x1080`() {
        assertNull(FilenameEpisodeMatcher.extractSeasonEpisode("Movie.1920x1080.mkv"))
    }

    // --- matches ---

    @Test
    fun `matches is case insensitive`() {
        assertTrue(FilenameEpisodeMatcher.matches("Show.S01E04.mkv", "show.s01e04.mkv"))
    }

    @Test
    fun `matches returns false for null`() {
        assertEquals(false, FilenameEpisodeMatcher.matches(null, "show.mkv"))
        assertEquals(false, FilenameEpisodeMatcher.matches("show.mkv", null))
    }
}
