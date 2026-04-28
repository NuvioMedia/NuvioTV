package com.nuvio.tv.core.sync

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.Collection
import com.nuvio.tv.domain.model.ContentType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogSyncSupportTest {

    @Test
    fun `build payload keeps disabled catalog when only legacy disable key exists`() {
        val addon = testAddon(
            id = "com.test.addon",
            baseUrl = "https://example.com/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState(
                disabledKeys = setOf(
                    homeLegacyDisabledCatalogKey(
                        addonBaseUrl = addon.baseUrl,
                        type = "movie",
                        catalogId = "top",
                        catalogName = "Top"
                    )
                )
            )
        )

        assertEquals(1, payload.items.size)
        assertEquals("com.test.addon", payload.items.single().addonId)
        assertFalse(payload.items.single().enabled)
    }

    @Test
    fun `build payload includes all visible catalogs even when saved order is empty`() {
        val firstAddon = testAddon(
            id = "com.test.first",
            baseUrl = "https://first.example/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )
        val secondAddon = testAddon(
            id = "com.test.second",
            baseUrl = "https://second.example/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.SERIES,
                    id = "latest",
                    name = "Latest",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(firstAddon, secondAddon),
            collections = emptyList(),
            localState = LocalHomeCatalogSettingsState()
        )

        assertEquals(
            listOf(
                homeCatalogKey("com.test.first", "movie", "top"),
                homeCatalogKey("com.test.second", "series", "latest")
            ),
            payload.items.map { homeCatalogKey(it.addonId, it.type, it.catalogId) }
        )
        assertTrue(payload.items.all { it.enabled })
    }

    @Test
    fun `build payload appends collections after catalogs and preserves disabled collections`() {
        val addon = testAddon(
            id = "com.test.addon",
            baseUrl = "https://example.com/manifest.json",
            catalogs = listOf(
                CatalogDescriptor(
                    type = ContentType.MOVIE,
                    id = "top",
                    name = "Top",
                    showInHome = true,
                    hasExplicitShowInHome = true
                )
            )
        )
        val collection = Collection(id = "col-1", title = "Favorites")

        val payload = buildHomeCatalogSyncPayload(
            addons = listOf(addon),
            collections = listOf(collection),
            localState = LocalHomeCatalogSettingsState(
                disabledKeys = setOf(homeCollectionKey("col-1"))
            )
        )

        assertEquals(2, payload.items.size)
        assertTrue(payload.items.last().isCollection)
        assertEquals("col-1", payload.items.last().collectionId)
        assertFalse(payload.items.last().enabled)
    }

    private fun testAddon(
        id: String,
        baseUrl: String,
        catalogs: List<CatalogDescriptor>
    ): Addon {
        return Addon(
            id = id,
            name = id,
            displayName = id,
            version = "1.0.0",
            description = null,
            logo = null,
            baseUrl = baseUrl,
            catalogs = catalogs,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }
}