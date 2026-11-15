package com.nuvio.tv.core.poster

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomPosterScreenTest {

    @Test
    fun unsetStorageDefaultsToAll() {
        assertEquals(CustomPosterScreen.ALL, CustomPosterScreen.fromStorageValue(null))
        assertNull(CustomPosterScreen.toStorageValue(CustomPosterScreen.ALL))
    }

    @Test
    fun emptySetPersistsAsNoneAndReadsBackEmpty() {
        assertEquals(CustomPosterScreen.NONE_KEY, CustomPosterScreen.toStorageValue(emptySet()))
        assertTrue(CustomPosterScreen.fromStorageValue(CustomPosterScreen.NONE_KEY).isEmpty())
        // Legacy blank value written before the fix should also mean none enabled
        assertTrue(CustomPosterScreen.fromStorageValue("").isEmpty())
        assertTrue(CustomPosterScreen.fromStorageValue("   ").isEmpty())
    }

    @Test
    fun partialSelectionRoundTrips() {
        val screens = setOf(CustomPosterScreen.HOME, CustomPosterScreen.SEARCH)
        val stored = CustomPosterScreen.toStorageValue(screens)
        assertEquals("home,search", stored)
        assertEquals(screens, CustomPosterScreen.fromStorageValue(stored))
    }

    @Test
    fun tvRecommendationsIsIncludedInDefaultAll() {
        assertTrue(CustomPosterScreen.TV_RECOMMENDATIONS in CustomPosterScreen.ALL)
        assertEquals(
            setOf(CustomPosterScreen.TV_RECOMMENDATIONS),
            CustomPosterScreen.fromStorageValue("tv_recommendations")
        )
    }
}
