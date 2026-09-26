package com.nuvio.tv.core.streams

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.Meta
import com.nuvio.tv.domain.model.RepositoryType
import com.nuvio.tv.domain.model.ScraperInfo
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.Video
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackAvailabilityTest {
    @Test
    fun `episode context converts TMDB tv to external series but keeps live types`() {
        assertEquals("series", externalStreamType("tv", season = 1, episode = 1))
        assertEquals("tv", externalStreamType("tv", season = null, episode = null))
        assertEquals("channel", externalStreamType(" Channel ", season = null, episode = null))
        assertEquals("movie", externalStreamType(" MOVIE ", season = null, episode = null))
        assertEquals("ppv", externalStreamType(" PPV ", season = null, episode = null))
    }

    @Test
    fun `no sources or metadata-only addons cannot play`() {
        assertFalse(PlaybackAvailability().canStream("movie", "tt123"))
        val metaAddon = addon().copy(resources = listOf(AddonResource("meta", listOf("movie"), null)))
        assertFalse(PlaybackAvailability(addons = listOf(metaAddon)).canStream("movie", "tt123"))
    }

    @Test
    fun `addon must be enabled and match the type and video prefix`() {
        val available = PlaybackAvailability(addons = listOf(addon()))
        assertTrue(available.canStream("movie", "tt123"))
        assertFalse(available.canStream("series", "tt123:1:1"))
        assertFalse(available.canStream("movie", "tmdb:123"))
        assertFalse(available.copy(addons = listOf(addon().copy(enabled = false))).canStream("movie", "tt123"))
    }

    @Test
    fun `resource prefixes override addon prefixes and missing prefixes fall back`() {
        val configured = addon().copy(idPrefixes = listOf("tmdb:"))
        assertTrue(configured.supportsStreamResource("movie", "tt123"))
        assertFalse(configured.supportsStreamResource("movie", "tmdb:123"))
        for (prefixes in listOf<List<String>?>(null, emptyList())) {
            val fallback = configured.copy(resources = listOf(AddonResource("stream", listOf("series"), prefixes)))
            assertTrue(fallback.supportsStreamResource("series", "tmdb:123:1:1"))
            assertFalse(fallback.supportsStreamResource("movie", "tt123"))
        }
    }

    @Test
    fun `declared custom stream types match literally`() {
        val customTypes = addon().copy(resources = listOf(AddonResource("stream", listOf("channel", "ppv"), null)))
        assertTrue(PlaybackAvailability(addons = listOf(customTypes)).canStream("channel", "channel:1"))
        assertTrue(PlaybackAvailability(addons = listOf(customTypes)).canStream("ppv", "ppv:1"))
        assertFalse(PlaybackAvailability(addons = listOf(customTypes)).canStream("tv", "channel:1"))
    }

    @Test
    fun `empty resource types do not imply support for every type`() {
        val emptyTypes = addon().copy(resources = listOf(AddonResource("stream", emptyList(), null)))
        assertFalse(PlaybackAvailability(addons = listOf(emptyTypes)).canStream("series", "tt123:1:1"))
    }

    @Test
    fun `addon stream resource matches types literally after trim and case normalization`() {
        val seriesAddon = addon().copy(resources = listOf(AddonResource("stream", listOf(" SERIES "), listOf("tt"))))
        val seriesAvailability = PlaybackAvailability(addons = listOf(seriesAddon))
        assertTrue(seriesAvailability.canStream("series", "tt123:1:1"))
        assertFalse(seriesAvailability.canStream("tv", "tt123:1:1"))

        val liveAddon = addon().copy(resources = listOf(AddonResource("stream", listOf("tv", "channel"), listOf("channel:"))))
        val liveAvailability = PlaybackAvailability(addons = listOf(liveAddon))
        assertTrue(liveAvailability.canStream("tv", "channel:1"))
        assertTrue(liveAvailability.canStream("channel", "channel:1"))
        assertFalse(liveAvailability.canStream("series", "channel:1"))
    }

    @Test
    fun `plugins match series and live tv types literally for both runtimes`() {
        for (runtime in RepositoryType.entries) {
            val seriesScraper = scraper().copy(type = runtime, supportedTypes = listOf("series"))
            val seriesAvailable = PlaybackAvailability(scrapers = listOf(seriesScraper))
            assertTrue(seriesAvailable.canStream("series", "tt123:1:1"))
            assertFalse(seriesAvailable.canStream("tv", "channel:1"))
            assertFalse(seriesAvailable.canStream("movie", "tt123"))
            assertFalse(seriesAvailable.copy(scrapers = listOf(seriesScraper.copy(enabled = false))).canStream("series", "tt123:1:1"))

            val liveAvailable = PlaybackAvailability(
                scrapers = listOf(seriesScraper.copy(supportedTypes = listOf("tv", "channel")))
            )
            assertTrue(liveAvailable.canStream("tv", "channel:1"))
            assertTrue(liveAvailable.canStream("channel", "channel:1"))
            assertFalse(liveAvailable.canStream("series", "tt123:1:1"))
        }
    }

    @Test
    fun `embedded streams allow only the matching video without installed sources`() {
        val video = video()
        val available = PlaybackAvailability()
        assertTrue(available.canStream("other", video.id, video = video))
        assertFalse(available.canStream("other", "different-video", video = video))
        assertFalse(available.canStream("other", video.id, video = video.copy(streams = emptyList())))
    }

    @Test
    fun `continue watching can use embedded streams from cached parent metadata`() {
        val video = video()
        val meta = mockk<Meta>()
        every { meta.videos } returns listOf(video)
        val available = PlaybackAvailability(cachedMeta = { type, id ->
            meta.takeIf { type == "other" && id == "parent" }
        })
        assertTrue(available.canStream("other", video.id, "parent"))
        assertFalse(available.canStream("other", "different-video", "parent"))
        assertFalse(available.canStream("other", video.id, "uncached-parent"))
    }

    private fun addon() = Addon(
        id = "addon", name = "Addon", version = "1", description = null, logo = null,
        baseUrl = "https://example.com", catalogs = emptyList(), types = emptyList(),
        resources = listOf(AddonResource("stream", listOf("movie"), listOf("tt")))
    )

    private fun scraper() = ScraperInfo(
        id = "scraper", name = "Scraper", description = "", version = "1", filename = "scraper.js",
        supportedTypes = listOf("movie"), enabled = true, manifestEnabled = true, logo = null,
        contentLanguage = emptyList(), repositoryId = "repo", formats = null
    )

    private fun video() = Video(
        id = "embedded-video", title = "Video", released = null, thumbnail = null,
        streams = listOf(mockk<Stream>()), season = null, episode = null, overview = null
    )
}
