package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperInfoTest {

    @Test
    fun `series normalizes to tv and matches series tv and show scraper types`() {
        assertTrue(scraperInfo(supportedTypes = listOf("series")).supportsType("series"))
        assertTrue(scraperInfo(supportedTypes = listOf("tv")).supportsType("series"))
        assertTrue(scraperInfo(supportedTypes = listOf("show")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("anime")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("series"))
    }

    @Test
    fun `tv matches series tv and show scraper types`() {
        assertTrue(scraperInfo(supportedTypes = listOf("tv")).supportsType("tv"))
        assertTrue(scraperInfo(supportedTypes = listOf("series")).supportsType("tv"))
        assertTrue(scraperInfo(supportedTypes = listOf("show")).supportsType("tv"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("tv"))
    }

    @Test
    fun `other normalizes to tv`() {
        assertTrue(scraperInfo(supportedTypes = listOf("tv")).supportsType("other"))
        assertTrue(scraperInfo(supportedTypes = listOf("series")).supportsType("other"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("other"))
    }

    @Test
    fun `movie only matches movie`() {
        assertTrue(scraperInfo(supportedTypes = listOf("movie")).supportsType("movie"))
        assertFalse(scraperInfo(supportedTypes = listOf("tv")).supportsType("movie"))
        assertFalse(scraperInfo(supportedTypes = listOf("series")).supportsType("movie"))
    }

    private fun scraperInfo(supportedTypes: List<String>): ScraperInfo {
        return ScraperInfo(
            id = "test",
            name = "Test",
            description = "Test scraper",
            version = "1.0.0",
            filename = "test.js",
            supportedTypes = supportedTypes,
            enabled = true,
            manifestEnabled = true,
            logo = null,
            contentLanguage = emptyList(),
            repositoryId = "repo",
            formats = null
        )
    }
}
