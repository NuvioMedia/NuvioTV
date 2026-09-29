package com.nuvio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class CatalogReferenceTest {
    @Test
    fun `catalog descriptor preserves manifest type while retaining semantic classification`() {
        val catalog = descriptor("Series")
        assertEquals(ContentType.SERIES, catalog.type)
        assertEquals("Series", catalog.apiType)
        assertEquals("series", ContentType.SERIES.toApiString())
    }

    @Test
    fun `catalog row preserves request type for pagination`() {
        val row = CatalogRow(
            addonId = "aio-metadata",
            addonName = "AIOMetadata",
            addonBaseUrl = "https://example.com",
            catalogId = "mdblist.123",
            catalogName = "Netflix Series",
            type = ContentType.SERIES,
            rawType = "Series",
            items = emptyList()
        )
        assertEquals("Series", row.apiType)
        assertEquals("Series", row.copy(currentPage = 1).apiType)
        assertEquals(row.stableKey(), row.copy(rawType = "series").stableKey())
    }

    @Test
    fun `saved website and older TV types resolve to advertised catalog`() {
        val catalog = descriptor("Series")
        for (savedType in listOf("Series", "series", "SERIES")) {
            assertSame(catalog, listOf(catalog).findCollectionCatalog(savedType, catalog.id))
        }
        val lowercase = descriptor("series")
        assertSame(lowercase, listOf(lowercase).findCollectionCatalog("Series", lowercase.id))
    }

    @Test
    fun `exact match takes precedence over compatible standard type`() {
        val lowercase = descriptor("series")
        val mixedCase = descriptor("Series")
        val catalogs = listOf(lowercase, mixedCase)
        assertSame(mixedCase, catalogs.findCollectionCatalog("Series", mixedCase.id))
        assertNull(catalogs.findCollectionCatalog("SERIES", mixedCase.id))
    }

    @Test
    fun `custom types and catalog ids stay distinct`() {
        for (rawType in listOf("\u200eMovies", "Movies", "Anime")) {
            val catalog = descriptor(rawType)
            assertEquals(rawType, catalog.apiType)
            assertSame(catalog, listOf(catalog).findCollectionCatalog(rawType, catalog.id))
            assertNull(listOf(catalog).findCollectionCatalog(rawType.lowercase(), catalog.id))
        }
        assertFalse(catalogTypesMatch("\u200eMovies", "Movies"))
        assertNull(listOf(descriptor("movie")).findCollectionCatalog("series", "mdblist.123"))
        assertNull(listOf(descriptor("Series")).findCollectionCatalog("Series", "missing"))
    }

    @Test
    fun `blank manifest type uses semantic fallback`() {
        assertEquals("series", descriptor("Series").copy(rawType = " ").apiType)
    }

    @Test
    fun `persisted keys retain compatibility without changing custom types`() {
        assertEquals(
            "aio-metadata_series_mdblist.123",
            catalogRowLegacyKey("aio-metadata", "Series", "mdblist.123")
        )
        assertEquals(
            collectionCatalogKey("aio-metadata", "series", "mdblist.123"),
            collectionCatalogKey("aio-metadata", "Series", "mdblist.123")
        )
        assertEquals(
            "aio-metadata_\u200eMovies_mdblist.123",
            catalogRowLegacyKey("aio-metadata", "\u200eMovies", "mdblist.123")
        )
    }

    @Test
    fun `saving legacy references writes manifest types for both sync representations`() {
        val source = AddonCatalogCollectionSource("aio-metadata", "series", "mdblist.123", "Drama")
        val collection = Collection(
            id = "collection-1",
            title = "Favorites",
            folders = listOf(CollectionFolder(id = "folder-1", title = "Shows", sources = listOf(source)))
        )
        val addon = Addon(
            id = source.addonId,
            name = "AIOMetadata",
            version = "1",
            description = null,
            logo = null,
            baseUrl = "https://example.com",
            catalogs = listOf(descriptor("Series")),
            types = listOf(ContentType.SERIES),
            resources = emptyList()
        )

        val saved = collection.withResolvedCatalogTypes(listOf(addon))
        assertEquals("Series", (saved.folders.single().sources.single() as AddonCatalogCollectionSource).type)
        assertEquals("Series", saved.folders.single().catalogSources.single().type)
        assertEquals("Drama", saved.folders.single().catalogSources.single().genre)
        assertEquals("series", source.type)
        assertEquals(collection, collection.withResolvedCatalogTypes(emptyList()))
        assertEquals(collection, collection.withResolvedCatalogTypes(listOf(addon.copy(enabled = false))))
    }

    private fun descriptor(rawType: String) = CatalogDescriptor(
        type = ContentType.fromString(rawType),
        rawType = rawType,
        id = "mdblist.123",
        name = "Netflix Series"
    )
}
