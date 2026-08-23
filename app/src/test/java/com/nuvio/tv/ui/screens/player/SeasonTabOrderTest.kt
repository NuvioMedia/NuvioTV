package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Test

class SeasonTabOrderTest {

    @Test
    fun `numbered seasons sort ascending`() {
        assertEquals(listOf(1, 2, 3), orderSeasonTabs(listOf(3, 1, 2)))
    }

    @Test
    fun `specials move to the end`() {
        assertEquals(listOf(1, 2, 3, 0), orderSeasonTabs(listOf(0, 3, 1, 2)))
    }

    @Test
    fun `tab index follows tab order rather than season number`() {
        val ordered = orderSeasonTabs(listOf(0, 1, 2, 3))

        assertEquals(0, seasonTabIndex(ordered, 1))
        assertEquals(2, seasonTabIndex(ordered, 3))
        assertEquals(3, seasonTabIndex(ordered, 0))
    }

    @Test
    fun `a season far down a long list resolves to its own index`() {
        val ordered = orderSeasonTabs((1..12).toList() + 0)

        assertEquals(8, seasonTabIndex(ordered, 9))
        assertEquals(12, seasonTabIndex(ordered, 0))
    }

    @Test
    fun `an unknown or missing season falls back to the first tab`() {
        val ordered = orderSeasonTabs(listOf(1, 2, 3))

        assertEquals(0, seasonTabIndex(ordered, 99))
        assertEquals(0, seasonTabIndex(ordered, null))
    }

    @Test
    fun `no seasons cannot produce a negative index`() {
        assertEquals(0, seasonTabIndex(emptyList(), 1))
    }

    @Test
    fun `a mid-list season opens with earlier seasons still on screen`() {
        val ordered = orderSeasonTabs((1..8).toList())
        val selected = seasonTabIndex(ordered, 5)

        assertEquals(2, seasonTabScrollIndex(selected, leadingContext = 2))
        assertEquals(3, ordered[seasonTabScrollIndex(selected, leadingContext = 2)])
    }

    @Test
    fun `early seasons stay pinned to the start rather than scrolling past zero`() {
        val ordered = orderSeasonTabs((1..8).toList())

        assertEquals(0, seasonTabScrollIndex(seasonTabIndex(ordered, 1), leadingContext = 2))
        assertEquals(0, seasonTabScrollIndex(seasonTabIndex(ordered, 2), leadingContext = 2))
        assertEquals(1, seasonTabScrollIndex(seasonTabIndex(ordered, 4), leadingContext = 2))
    }

    @Test
    fun `specials at the end still show the seasons before them`() {
        val ordered = orderSeasonTabs((1..8).toList() + 0)
        val selected = seasonTabIndex(ordered, 0)

        assertEquals(6, seasonTabScrollIndex(selected, leadingContext = 2))
    }
}
