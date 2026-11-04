package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class EpisodeMismatchDetectorTest {

    @Test
    fun `detects Frieren EMBER S01E01 resolving to S01E16 from Content-Disposition or Matroska title`() {
        val contentDisposition = EpisodeMismatchDetector.extractContentDispositionFilename(
            """attachment; filename="S01E16-Long-Lived Friends [21E3FD7E].mkv"; filename*=UTF-8''S01E16-Long-Lived%20Friends%20%5B21E3FD7E%5D.mkv"""
        )
        assertEquals("S01E16-Long-Lived Friends [21E3FD7E].mkv", contentDisposition)

        val mismatchFromHeader = EpisodeMismatchDetector.detectMismatch(
            expectedSeason = 1,
            expectedEpisode = 1,
            streamUrl = "https://store-027.tb-cdn.st/dl/abc123",
            candidateLabels = listOf(contentDisposition)
        )
        assertNotNull(mismatchFromHeader)
        assertEquals("S01E01", mismatchFromHeader?.formatExpectedCode())
        assertEquals("S01E16", mismatchFromHeader?.formatDetectedCode())

        val mismatchFromMpvTitle = EpisodeMismatchDetector.detectMismatch(
            expectedSeason = 1,
            expectedEpisode = 1,
            streamUrl = null,
            candidateLabels = listOf("Frieren-Beyond.Journey's.End.S01E16.1080p.Dual.Audio.BDRip.10.bits.DD+.x265-EMBER")
        )
        assertNotNull(mismatchFromMpvTitle)
        assertEquals("S01E16", mismatchFromMpvTitle?.formatDetectedCode())
    }

    @Test
    fun `detects Vivy EMBER S01E01 resolving to S01E11`() {
        val mismatch = EpisodeMismatchDetector.detectMismatch(
            expectedSeason = 1,
            expectedEpisode = 1,
            streamUrl = null,
            candidateLabels = listOf("S01E11-World's End Modulation - April 11, 2161 [50D25998].mkv")
        )
        assertNotNull(mismatch)
        assertEquals("S01E01", mismatch?.formatExpectedCode())
        assertEquals("S01E11", mismatch?.formatDetectedCode())
    }

    @Test
    fun `detects Vivy Salieri S01E01 resolving to anime episode 08`() {
        val mismatch = EpisodeMismatchDetector.detectMismatch(
            expectedSeason = 1,
            expectedEpisode = 1,
            streamUrl = null,
            candidateLabels = listOf("[Salieri] Vivy - Fluorite Eye's Song - 08 - 1080p (HDR) [Dual-Audio].mkv")
        )
        assertNotNull(mismatch)
        assertEquals("S01E01", mismatch?.formatExpectedCode())
        assertEquals("E08", mismatch?.formatDetectedCode())
    }

    @Test
    fun `returns null when resolved episode matches expected episode`() {
        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 1,
                expectedEpisode = 1,
                streamUrl = null,
                candidateLabels = listOf("S01E01-My Code Name Is Diva [62C6569C].mkv")
            )
        )
        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 1,
                expectedEpisode = 1,
                streamUrl = null,
                candidateLabels = listOf("[Salieri] Vivy - Fluorite Eye's Song - 01 - 1080p (HDR) [Dual-Audio].mkv")
            )
        )
    }

    @Test
    fun `does not flag season 2+ when filename only has seasonless absolute episode number`() {
        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 2,
                expectedEpisode = 1,
                streamUrl = null,
                candidateLabels = listOf("[SubsPlease] Anime Title - 14 (1080p) [ABCD1234].mkv")
            )
        )
    }

    @Test
    fun `handles semicolons inside quoted Content-Disposition filenames and multi-episode files`() {
        val filename = EpisodeMismatchDetector.extractContentDispositionFilename(
            """attachment; filename="Steins;Gate - S01E16 [1080p].mkv""""
        )
        assertEquals("Steins;Gate - S01E16 [1080p].mkv", filename)

        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 1,
                expectedEpisode = 2,
                streamUrl = null,
                candidateLabels = listOf("Show.S01E01E02.1080p.WEB-DL.mkv")
            )
        )
        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 1,
                expectedEpisode = 2,
                streamUrl = null,
                candidateLabels = listOf("Show.S01E01-E02.1080p.WEB-DL.mkv")
            )
        )
        assertNull(
            EpisodeMismatchDetector.detectMismatch(
                expectedSeason = 1,
                expectedEpisode = 2,
                streamUrl = null,
                candidateLabels = listOf("Show_S01E01-E02_1080p.mkv")
            )
        )
    }
}
