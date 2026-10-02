package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.ui.util.StableList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeIdentityRelocationTest {

    @Test
    fun `focused card is followed by key when items are inserted`() {
        assertEquals(
            2,
            resolveFocusedIndex(FocusedCard("b", 1), 1, listOf("x", "a", "b"), isActiveRow = false)
        )
    }

    @Test
    fun `vanished card inside the list leaves focus on the card that took its place`() {
        val keys = (0 until 20).map { "new$it" }

        assertEquals(5, resolveFocusedIndex(FocusedCard("old5", 5), 5, keys, isActiveRow = false))
        assertEquals(19, resolveFocusedIndex(FocusedCard("old19", 19), 19, keys, isActiveRow = false))
    }

    @Test
    fun `vanished card past the end of the list resets focus, except on the active row`() {
        val keys = (0 until 20).map { "new$it" }

        assertEquals(0, resolveFocusedIndex(FocusedCard("old35", 35), 35, keys, isActiveRow = false))
        assertEquals(35, resolveFocusedIndex(FocusedCard("old35", 35), 35, keys, isActiveRow = true))
    }

    @Test
    fun `row replaced then grown back does not land on the unrelated card at the old index`() {
        val replaced = (0 until 20).map { "new$it" }
        val afterReplace = resolveFocusedIndex(FocusedCard("old35", 35), 35, replaced, isActiveRow = false)
        val grown = (0 until 20).map { "newer$it" } + replaced

        assertEquals(0, afterReplace)
        assertEquals(
            20,
            resolveFocusedIndex(FocusedCard(replaced[afterReplace], afterReplace), afterReplace, grown, isActiveRow = false)
        )
    }

    @Test
    fun `a card recorded for another index is not followed`() {
        // The index moved without its card being recorded: the stale key must not pull focus back.
        assertEquals(
            3,
            resolveFocusedIndex(FocusedCard("a", 0), 3, listOf("x", "a", "b", "c"), isActiveRow = false)
        )
        assertEquals(1, resolveFocusedIndex(null, 1, listOf("x", "a"), isActiveRow = false))
    }

    @Test
    fun `second copy of a title is followed by its own key`() {
        assertEquals(
            3,
            resolveFocusedIndex(FocusedCard("a_1", 2), 2, listOf("x", "a_0", "b", "a_1"), isActiveRow = false)
        )
    }

    @Test
    fun `focus target is the focused card wherever the row still composes it`() {
        // Relocated 0 -> 2, but the row has not re-measured: only the cards of its old window exist.
        val composed = mapOf("movie:a" to "A", "movie:b" to "B")

        assertEquals(
            "A",
            resolveRowFocusTarget(listOf("movie:x", "movie:y", "movie:a", "movie:b"), 2, composed)
        )
    }

    @Test
    fun `focus target falls back to the first card, then to none`() {
        val keys = listOf("movie:a", "movie:b", "movie:c")

        assertEquals("A", resolveRowFocusTarget(keys, 2, mapOf("movie:a" to "A")))
        assertEquals("A", resolveRowFocusTarget(keys, 9, mapOf("movie:a" to "A")))
        assertNull(resolveRowFocusTarget(keys, 2, mapOf("movie:b" to "B")))
        assertNull(resolveRowFocusTarget(emptyList(), 0, mapOf("movie:a" to "A")))
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
