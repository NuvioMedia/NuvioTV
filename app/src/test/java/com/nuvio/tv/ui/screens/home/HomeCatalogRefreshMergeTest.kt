package com.nuvio.tv.ui.screens.home

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
    fun `a reorder, a removal or a full turnover is a restructure`() {
        val reordered = listOf(loaded[1], loaded[0]) + loaded.subList(2, 20)
        val removed = loaded.take(20) - loaded[3] + loaded[20]
        val turnover = (0 until 20).map { "movie:new$it" }

        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(loaded, reordered))
        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(loaded, removed))
        assertEquals(CatalogRefreshChange.Restructure, classifyCatalogRefresh(loaded, turnover))
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
}
