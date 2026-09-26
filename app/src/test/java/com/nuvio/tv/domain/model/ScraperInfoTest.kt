package com.nuvio.tv.domain.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScraperInfoTest {

    @Test
    fun `manifest defaults to movie and series types`() {
        val manifest = ScraperManifestInfo(
            id = "test",
            name = "Test",
            version = "1.0.0",
            filename = "test.js"
        )

        assertTrue(manifest.supportedTypes == listOf("movie", "series"))
    }

    @Test
    fun `series matches only series after trimming and case normalization`() {
        assertTrue(scraperInfo(supportedTypes = listOf("series")).supportsType("series"))
        assertTrue(scraperInfo(supportedTypes = listOf(" SERIES ")).supportsType(" series "))
        assertFalse(scraperInfo(supportedTypes = listOf("tv")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("show")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("anime")).supportsType("series"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("series"))
    }

    @Test
    fun `tv matches only tv scraper types`() {
        assertTrue(scraperInfo(supportedTypes = listOf("tv")).supportsType("tv"))
        assertTrue(scraperInfo(supportedTypes = listOf(" TV ")).supportsType(" tv "))
        assertFalse(scraperInfo(supportedTypes = listOf("series")).supportsType("tv"))
        assertFalse(scraperInfo(supportedTypes = listOf("show")).supportsType("tv"))
        assertFalse(scraperInfo(supportedTypes = listOf("movie")).supportsType("tv"))
    }

    @Test
    fun `unknown and channel types remain literal`() {
        assertTrue(scraperInfo(supportedTypes = listOf("ppv")).supportsType("ppv"))
        assertFalse(scraperInfo(supportedTypes = listOf("series")).supportsType("other"))
        assertTrue(scraperInfo(supportedTypes = listOf("channel")).supportsType("channel"))
        assertFalse(scraperInfo(supportedTypes = listOf("tv")).supportsType("channel"))
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
