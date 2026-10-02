package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.stableItemKeys
import com.nuvio.tv.domain.model.stableKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCatalogRefreshMergeTest {

    private val loaded = (0 until 40).map { "movie:old$it" }

    @Test
    fun `page 1 matching the head of the row is unchanged`() {
        assertEquals(CatalogRefreshChange.Unchanged, classifyCatalogRefresh(loaded, loaded.take(20)))
    }

    @Test
    fun `new items in front of the same head are a prepend`() {
        val fresh = listOf("movie:n0", "movie:n1", "movie:n2") + loaded.take(17)

        assertEquals(CatalogRefreshChange.Prepend(3), classifyCatalogRefresh(loaded, fresh))
    }

    @Test
    fun `a title moved back to the front is a prepend that names it`() {
        val bumped = listOf(loaded[7]) + loaded.take(20).filter { it != loaded[7] }
        val fromPage2 = listOf(loaded[30]) + loaded.take(19)

        assertEquals(
            CatalogRefreshChange.Prepend(1, setOf(loaded[7])),
            classifyCatalogRefresh(loaded, bumped)
        )
        assertEquals(
            CatalogRefreshChange.Prepend(1, setOf(loaded[30])),
            classifyCatalogRefresh(loaded, fromPage2)
        )
    }

    @Test
    fun `new and moved titles in front count separately`() {
        val fresh = listOf("movie:n0", loaded[7], "movie:n1") + loaded.take(18).filter { it != loaded[7] }
        val change = classifyCatalogRefresh(loaded, fresh) as CatalogRefreshChange.Prepend

        assertEquals(3, change.headCount)
        assertEquals(setOf(loaded[7]), change.moved)
        assertEquals(2, change.addedCount)
    }

    @Test
    fun `a page 1 cut short by the end of the catalog still reads as a prepend`() {
        val short = listOf("movie:n0", "movie:n1")
        val current = listOf("movie:a", "movie:b")

        assertEquals(CatalogRefreshChange.Prepend(1, emptySet()), classifyCatalogRefresh(current, listOf("movie:n0", "movie:a")))
        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(current, short))
    }

    @Test
    fun `an empty page 1 leaves the row unchanged`() {
        assertEquals(CatalogRefreshChange.Unchanged, classifyCatalogRefresh(loaded, emptyList()))
    }

    @Test
    fun `item keys tell copies of a title apart, by type and by occurrence`() {
        val row = CatalogRow(
            addonId = "addon",
            addonName = "Addon",
            addonBaseUrl = "https://example.test",
            catalogId = "catalog",
            catalogName = "Catalog",
            type = ContentType.MOVIE,
            items = listOf(
                preview("tt1", ContentType.MOVIE),
                preview("tt1", ContentType.SERIES),
                preview("tt1", ContentType.MOVIE)
            )
        )
        val prefix = row.stableKey()

        assertEquals(
            listOf("${prefix}_movie:tt1", "${prefix}_series:tt1", "${prefix}_movie:tt1#1"),
            row.stableItemKeys()
        )
    }

    @Test
    fun `a removal or a full turnover is a restructure`() {
        val removed = loaded.take(20) - loaded[3] + loaded[20]
        val turnover = (0 until 20).map { "movie:new$it" }

        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(loaded, removed))
        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(loaded, turnover))
    }

    @Test
    fun `on the focused row a moved title on screen is held back, one off screen is not`() {
        val moved = setOf(loaded[12], loaded[30], loaded[2])

        // Focus on card 10: cards 9..18 count as on screen.
        assertEquals(setOf(loaded[12]), movedTitlesHeldBack(moved, loaded, focusedIndex = 10))
        assertEquals(setOf(loaded[10]), movedTitlesHeldBack(setOf(loaded[10]), loaded, focusedIndex = 10))
    }

    @Test
    fun `every moved title is held back when the focused card is unknown`() {
        val moved = setOf(loaded[12], loaded[30])

        assertEquals(moved, movedTitlesHeldBack(moved, loaded, focusedIndex = -1))
        assertEquals(emptySet<String>(), movedTitlesHeldBack(emptySet(), loaded, focusedIndex = -1))
    }

    @Test
    fun `restructured row is kept while its focus sits past page 1 on a card page 1 lacks`() {
        assertTrue(keepsRowOnRestructure(false, false, focusedIndex = 35, focusedInFresh = false, freshSize = 20))
    }

    @Test
    fun `restructured row is rebuilt when its focus is within page 1, unknown, or followed into it`() {
        assertFalse(keepsRowOnRestructure(false, false, focusedIndex = 5, focusedInFresh = false, freshSize = 20))
        assertFalse(keepsRowOnRestructure(false, false, focusedIndex = 0, focusedInFresh = false, freshSize = 20))
        assertFalse(keepsRowOnRestructure(false, false, focusedIndex = -1, focusedInFresh = false, freshSize = 20))
        assertFalse(keepsRowOnRestructure(false, false, focusedIndex = 35, focusedInFresh = true, freshSize = 20))
    }

    @Test
    fun `focused row is kept, and a refresh asked by the user rebuilds everything`() {
        assertTrue(keepsRowOnRestructure(true, false, focusedIndex = 2, focusedInFresh = true, freshSize = 20))
        assertFalse(keepsRowOnRestructure(true, true, focusedIndex = 35, focusedInFresh = false, freshSize = 20))
        assertFalse(keepsRowOnRestructure(false, true, focusedIndex = 35, focusedInFresh = false, freshSize = 20))
    }

    private fun preview(id: String, type: ContentType) = MetaPreview(
        id = id,
        type = type,
        name = id,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )
}
