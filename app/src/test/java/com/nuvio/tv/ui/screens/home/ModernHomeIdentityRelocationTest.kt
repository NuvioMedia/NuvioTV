package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeIdentityRelocationTest {

    @Test
    fun `focused item follows its identity when items are inserted`() {
        val relocatedIndex = findRelocatedItemIndex(
            previousIdentities = listOf("movie:a", "movie:b"),
            currentIdentities = listOf("movie:x", "movie:a", "movie:b"),
            storedIndex = 1
        )

        assertEquals(2, relocatedIndex)
    }

    @Test
    fun `missing previous or current identity leaves the stored index unchanged`() {
        assertNull(
            findRelocatedItemIndex(
                previousIdentities = null,
                currentIdentities = listOf("movie:a"),
                storedIndex = 0
            )
        )
        assertNull(
            findRelocatedItemIndex(
                previousIdentities = listOf("movie:a"),
                currentIdentities = listOf("movie:b"),
                storedIndex = 0
            )
        )
    }

    @Test
    fun `presentation lookups retain ordered payload identities`() {
        val row = HeroCarouselRow(
            key = "catalog",
            title = "Catalog",
            globalRowIndex = 0,
            items = StableList(
                listOf(
                    catalogItem(key = "first", id = "a", type = "movie"),
                    catalogItem(key = "second", id = "b", type = "series")
                )
            )
        )

        val identities = buildCarouselRowLookups(listOf(row))
            .itemIdentitiesByRow["catalog"]
            ?.list

        assertEquals(listOf("movie:a", "series:b"), identities)
    }

    private fun catalogItem(key: String, id: String, type: String): ModernCarouselItem {
        return ModernCarouselItem(
            key = key,
            title = id,
            subtitle = null,
            imageUrl = null,
            heroPreview = HeroPreview(
                title = id,
                logo = null,
                description = null,
                contentTypeText = null,
                yearText = null,
                imdbText = null,
                genres = StableList(),
                poster = null,
                backdrop = null,
                imageUrl = null
            ),
            payload = ModernPayload.Catalog(
                focusKey = key,
                itemId = id,
                itemType = type,
                addonBaseUrl = "https://example.test/manifest.json",
                trailerTitle = id,
                trailerReleaseInfo = null,
                trailerApiType = type
            )
        )
    }
}
