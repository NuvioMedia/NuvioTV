package com.nuvio.tv.core.server

import org.junit.Assert.assertEquals
import org.junit.Test

class AddonConfigServerTest {

    @Test
    fun `collections only mode preserves existing addon and catalog changes`() {
        val currentState = AddonConfigServer.PageState(
            addons = listOf(
                AddonConfigServer.AddonInfo(
                    url = "https://primary.example",
                    name = "Primary",
                    description = null
                )
            ),
            catalogs = listOf(
                AddonConfigServer.CatalogInfo(
                    key = "catalog_a",
                    disableKey = "catalog_a",
                    catalogName = "Featured",
                    addonName = "Primary",
                    type = "movie",
                    isDisabled = false
                ),
                AddonConfigServer.CatalogInfo(
                    key = "collection_saved",
                    disableKey = "collection_saved",
                    catalogName = "Saved",
                    addonName = "1 folder",
                    type = "collection",
                    isDisabled = true
                )
            )
        )
        val proposedChange = AddonConfigServer.PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[{\"id\":\"new\"}]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonConfigServer.WebConfigMode.COLLECTIONS_ONLY,
            proposedChange = proposedChange,
            currentState = currentState
        )

        assertEquals(listOf("https://primary.example"), sanitized.proposedUrls)
        assertEquals(listOf("catalog_a", "collection_saved"), sanitized.proposedCatalogOrderKeys)
        assertEquals(listOf("collection_saved"), sanitized.proposedDisabledCatalogKeys)
        assertEquals("[{\"id\":\"new\"}]", sanitized.proposedCollectionsJson)
        assertEquals(listOf("collection_new"), sanitized.proposedDisabledCollectionKeys)
    }

    @Test
    fun `full mode keeps proposed addon and catalog changes`() {
        val proposedChange = AddonConfigServer.PendingAddonChange(
            proposedUrls = listOf("https://other.example"),
            proposedCatalogOrderKeys = listOf("catalog_b"),
            proposedDisabledCatalogKeys = listOf("catalog_b"),
            proposedCollectionsJson = "[]",
            proposedDisabledCollectionKeys = listOf("collection_new")
        )

        val sanitized = sanitizePendingAddonChange(
            mode = AddonConfigServer.WebConfigMode.FULL,
            proposedChange = proposedChange,
            currentState = AddonConfigServer.PageState(
                addons = emptyList(),
                catalogs = emptyList()
            )
        )

        assertEquals(proposedChange, sanitized)
    }
}