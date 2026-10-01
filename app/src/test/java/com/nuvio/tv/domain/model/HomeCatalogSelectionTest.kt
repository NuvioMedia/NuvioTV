package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogSelectionTest {
    private val source = PluginSourceRef(RepositoryType.NUVIO_JS, "repo", "a")
    private val other = source.copy(scraperId = "b")
    private val both = PluginCapabilities(supportsCatalog = true, supportsStreams = true)

    @Test
    fun `catalog with streams selects itself while catalog only and addon select all`() {
        val state = HomeCatalogSelections().selectCatalog(HomeCatalogSelection.Plugin(source), setOf(source))
        assertEquals(HomeStreamSelection.Plugin(source), state.streams)
        assertEquals(HomeStreamSelection.All, state.selectCatalog(HomeCatalogSelection.Plugin(other), setOf(source)).streams)
        assertEquals(HomeStreamSelection.All, state.selectCatalog(HomeCatalogSelection.Addon("nuvio", "https://example.test"), setOf(source)).streams)
    }

    @Test
    fun `manual stream choice keeps catalog but next catalog change resets stream choice`() {
        val catalog = HomeCatalogSelection.Plugin(source)
        val initial = HomeCatalogSelections().selectCatalog(catalog, setOf(source, other))
        val manual = initial.selectStreams(HomeStreamSelection.Plugin(other))
        assertEquals(catalog, manual.catalog)
        assertEquals(HomeStreamSelection.Plugin(other), manual.streams)
        assertEquals(HomeStreamSelection.Plugin(source), manual.selectCatalog(catalog, setOf(source, other)).streams)
    }

    @Test
    fun `all excludes catalog only plugins and specific choice excludes other providers`() {
        assertFalse(HomeStreamSelection.All.includes(source, PluginCapabilities(supportsCatalog = true)))
        assertTrue(HomeStreamSelection.All.includes(source, both))
        assertTrue(HomeStreamSelection.All.includes(other, PluginCapabilities(supportsStreams = true)))
        assertFalse(HomeStreamSelection.Plugin(source).includes(other, both))
    }

    @Test
    fun `catalog URL and opaque identity only go to their own selected provider`() {
        val content = PluginContentRef(source, "opaque", ContentType.MOVIE, "https://example.test/Film")
        assertEquals(content, content.forStreamSource(source, HomeStreamSelection.Plugin(source), both))
        assertEquals(content, content.forStreamSource(source, HomeStreamSelection.All, both))
        assertNull(content.forStreamSource(other, HomeStreamSelection.All, both))
        assertNull(content.forStreamSource(source, HomeStreamSelection.Plugin(other), both))
        assertNull(content.forStreamSource(source, HomeStreamSelection.All, PluginCapabilities(supportsCatalog = true)))
    }
}
