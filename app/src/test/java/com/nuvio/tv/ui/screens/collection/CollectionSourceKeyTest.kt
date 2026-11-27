package com.nuvio.tv.ui.screens.collection

import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TmdbCollectionSource
import com.nuvio.tv.domain.model.TmdbCollectionSourceType
import com.nuvio.tv.domain.model.TraktCollectionSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CollectionSourceKeyTest {

    @Test
    fun `tmdb sources with distinct titles keep stable keys when reordered`() {
        val first = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Action Picks",
            mediaType = TmdbCollectionMediaType.MOVIE
        )
        val second = TmdbCollectionSource(
            sourceType = TmdbCollectionSourceType.DISCOVER,
            title = "Sci-Fi Picks",
            mediaType = TmdbCollectionMediaType.MOVIE
        )

        val before = collectionSourceKeys(listOf(first, second))
        val after = collectionSourceKeys(listOf(second, first))

        assertNotEquals(before[0], before[1])
        assertEquals(before[0], after[1])
        assertEquals(before[1], after[0])
    }

    @Test
    fun `trakt sources with distinct titles keep stable keys when reordered`() {
        val first = TraktCollectionSource(
            title = "Weekend List",
            traktListId = 42L,
            mediaType = TmdbCollectionMediaType.MOVIE
        )
        val second = TraktCollectionSource(
            title = "Favorites Copy",
            traktListId = 42L,
            mediaType = TmdbCollectionMediaType.MOVIE
        )

        val before = collectionSourceKeys(listOf(first, second))
        val after = collectionSourceKeys(listOf(second, first))

        assertNotEquals(before[0], before[1])
        assertEquals(before[0], after[1])
        assertEquals(before[1], after[0])
    }
}
