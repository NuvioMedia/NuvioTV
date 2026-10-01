package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class PluginCatalogTest {
    private val source = PluginSourceRef(RepositoryType.NUVIO_JS, "repo", "scraper")
    private val content = PluginContentRef(source, "Film/İstanbul|42", ContentType.MOVIE)

    @Test
    fun `v1 identity encoding remains stable across releases`() {
        assertEquals(
            "plugin:v1:a39c82fdd1d6623aea14fb0daa6bfda6cf7880afc29a88d197a2eee43756c118",
            content.stableId()
        )
    }

    @Test
    fun `metadata enrichment and URL changes do not replace content identity`() {
        val enriched = content.copy(
            url = "https://example.test/Film/İstanbul?x=1&y=2",
            externalIds = PluginExternalIds(tmdbId = "42", imdbId = "tt1234567")
        )
        assertEquals(content.stableId(), enriched.stableId())
        assertEquals(content, PluginCatalogItem(content, "Film").toMetaPreview().pluginContentRef)
        assertEquals(enriched, PluginCatalogItem(enriched, "New title").toMetaPreview().pluginContentRef)
    }

    @Test
    fun `catalog only item needs neither URL nor external identifiers`() {
        val preview = PluginCatalogItem(content, "Film", year = 2024).toMetaPreview()
        assertNull(preview.pluginContentRef?.url)
        assertNull(preview.imdbId)
        assertEquals("2024", preview.releaseInfo)
        assertEquals(content.stableId(), preview.id)
    }

    @Test
    fun `repository provider type and content case isolate identities`() {
        val alternatives = listOf(
            content.copy(source = source.copy(repositoryId = "other")),
            content.copy(source = source.copy(scraperId = "other")),
            content.copy(type = ContentType.SERIES),
            content.copy(contentId = content.contentId.lowercase()),
            content.copy(source = PluginSourceRef(RepositoryType.EXTERNAL_DEX, "repo", "scraper", "Api A")),
            content.copy(source = PluginSourceRef(RepositoryType.EXTERNAL_DEX, "repo", "scraper", "Api B"))
        )
        val ids = (alternatives + content).map { it.stableId() }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `identity fields containing delimiters cannot alias each other`() {
        val first = content.copy(source = source.copy(repositoryId = "a|b", scraperId = "c"))
        val second = content.copy(source = source.copy(repositoryId = "a", scraperId = "b|c"))
        assertNotEquals(first.stableId(), second.stableId())
    }

    @Test
    fun `invalid provider identity and unsupported content type are rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            PluginSourceRef(RepositoryType.EXTERNAL_DEX, "repo", "scraper")
        }
        assertThrows(IllegalArgumentException::class.java) { content.copy(contentId = " ") }
        assertThrows(IllegalArgumentException::class.java) { content.copy(type = ContentType.UNKNOWN) }
    }

    @Test
    fun `plugin row identity includes provider but not title page or metadata`() {
        val first = row(source)
        assertEquals(first.stableKey(), first.copy(catalogName = "Translated", pluginNextPageToken = "2").stableKey())
        assertNotEquals(first.stableKey(), first.copy(pluginSource = source.copy(scraperId = "other")).stableKey())
        assertNotEquals(first.stableKey(), first.copy(catalogId = "other").stableKey())
        assertEquals(first.items.single().id, first.copy(catalogId = "other").items.single().id)
    }

    @Test
    fun `addon keys remain unchanged and plugin pages cannot use skip pagination`() {
        val addon = row(null)
        assertEquals(catalogRowStableKey("addon", "https://example.test", "movie", "popular"), addon.stableKey())
        assertEquals(catalogRowLegacyKey("addon", "movie", "popular"), addon.legacyKey())
        val plugin = row(source)
        assertThrows(IllegalArgumentException::class.java) { plugin.nextCatalogSkip() }
        assertThrows(IllegalArgumentException::class.java) { addon.mergeCatalogPage(plugin) }
        assertThrows(IllegalArgumentException::class.java) { plugin.mergeCatalogPage(addon) }
    }

    private fun row(source: PluginSourceRef?) = CatalogRow(
        addonId = "addon",
        addonName = "Addon",
        addonBaseUrl = "https://example.test",
        catalogId = "popular",
        catalogName = "Popular",
        type = ContentType.MOVIE,
        items = listOf(PluginCatalogItem(content, "Film").toMetaPreview()),
        pluginSource = source
    )
}
