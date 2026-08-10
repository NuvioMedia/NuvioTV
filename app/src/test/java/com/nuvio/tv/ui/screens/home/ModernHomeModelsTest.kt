package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ModernHomeModelsTest {
    @Test
    fun `removing a middle item targets the card that shifts into its slot`() {
        val items = (0..9).map(::inProgress)

        val target = continueWatchingRemovalFocusTarget(
            items = items,
            removedItem = items[6],
            lastFocusedIndex = 6
        )

        assertEquals(6, target.index)
        assertEquals(continueWatchingItemKey(items[7]), target.itemKey)
    }

    @Test
    fun `removing the last item targets the new last card`() {
        val items = (0..9).map(::inProgress)

        val target = continueWatchingRemovalFocusTarget(
            items = items,
            removedItem = items[9],
            lastFocusedIndex = 9
        )

        assertEquals(8, target.index)
        assertEquals(continueWatchingItemKey(items[8]), target.itemKey)
    }

    @Test
    fun `removing the only item has no focus target`() {
        val item = inProgress(0)

        val target = continueWatchingRemovalFocusTarget(
            items = listOf(item),
            removedItem = item,
            lastFocusedIndex = 0
        )

        assertNull(target.index)
        assertNull(target.itemKey)
    }

    @Test
    fun `removing the first item targets the former second item at index 0`() {
        val items = (0..4).map(::inProgress)

        val target = continueWatchingRemovalFocusTarget(
            items = items,
            removedItem = items[0],
            lastFocusedIndex = 0
        )

        // Same visual slot: next card slides into index 0.
        assertEquals(0, target.index)
        assertEquals(continueWatchingItemKey(items[1]), target.itemKey)
    }

    @Test
    fun `removing penultimate item targets the last item`() {
        val items = (0..4).map(::inProgress)

        val target = continueWatchingRemovalFocusTarget(
            items = items,
            removedItem = items[3],
            lastFocusedIndex = 3
        )

        assertEquals(3, target.index)
        assertEquals(continueWatchingItemKey(items[4]), target.itemKey)
    }

    private fun inProgress(index: Int) = ContinueWatchingItem.InProgress(
        progress = WatchProgress(
            contentId = "show-$index",
            contentType = "series",
            name = "Show $index",
            poster = null,
            backdrop = null,
            logo = null,
            videoId = "video-$index",
            season = 1,
            episode = index + 1,
            episodeTitle = null,
            position = 100L,
            duration = 1_000L,
            lastWatched = index.toLong()
        )
    )
}
