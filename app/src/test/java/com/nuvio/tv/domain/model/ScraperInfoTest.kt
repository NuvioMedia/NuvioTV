package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperInfoTest {

    @Test
    fun `series matches series tv and anime scraper types`() {
        assertTrue(scraperInfo(supportedTypes = listOf("series")).supportsType("series"))
        assertTrue(scraperInfo(supportedTypes = listOf("tv")).supportsType("series"))
        assertTrue(scraperInfo(supportedTypes = listOf("anime")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("series"))
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
