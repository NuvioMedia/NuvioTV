package com.omnio.tv.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgeRatingTierTest {

    @Test
    fun `normalize returns null for blank`() {
        assertNull(AgeRatingTier.normalize(null))
        assertNull(AgeRatingTier.normalize(""))
        assertNull(AgeRatingTier.normalize("   "))
    }

    @Test
    fun `normalize maps MPAA codes`() {
        assertEquals(AgeRatingTier.G, AgeRatingTier.normalize("G"))
        assertEquals(AgeRatingTier.PG, AgeRatingTier.normalize("PG"))
        assertEquals(AgeRatingTier.PG_13, AgeRatingTier.normalize("PG-13"))
        assertEquals(AgeRatingTier.PG_13, AgeRatingTier.normalize("pg13"))
        assertEquals(AgeRatingTier.R, AgeRatingTier.normalize("R"))
        assertEquals(AgeRatingTier.NC_17, AgeRatingTier.normalize("NC-17"))
    }

    @Test
    fun `normalize maps TV codes`() {
        assertEquals(AgeRatingTier.G, AgeRatingTier.normalize("TV-Y7"))
        assertEquals(AgeRatingTier.PG, AgeRatingTier.normalize("TV-PG"))
        assertEquals(AgeRatingTier.TV_14, AgeRatingTier.normalize("TV-14"))
        assertEquals(AgeRatingTier.R, AgeRatingTier.normalize("TV-MA"))
    }

    @Test
    fun `normalize maps numeric and BBFC-style ratings`() {
        assertEquals(AgeRatingTier.G, AgeRatingTier.normalize("U"))
        assertEquals(AgeRatingTier.PG_13, AgeRatingTier.normalize("12A"))
        assertEquals(AgeRatingTier.PG_13, AgeRatingTier.normalize("13+"))
        assertEquals(AgeRatingTier.TV_14, AgeRatingTier.normalize("14"))
        assertEquals(AgeRatingTier.TV_14, AgeRatingTier.normalize("15"))
        assertEquals(AgeRatingTier.R, AgeRatingTier.normalize("17+"))
        assertEquals(AgeRatingTier.R, AgeRatingTier.normalize("18+"))
    }

    @Test
    fun `normalize returns null for unrecognized`() {
        assertNull(AgeRatingTier.normalize("Unrated"))
        assertNull(AgeRatingTier.normalize("not-a-rating"))
    }

    @Test
    fun `allowsUpTo enforces ceiling correctly`() {
        assertTrue(AgeRatingTier.PG_13.allowsUpTo(AgeRatingTier.G))
        assertTrue(AgeRatingTier.PG_13.allowsUpTo(AgeRatingTier.PG))
        assertTrue(AgeRatingTier.PG_13.allowsUpTo(AgeRatingTier.PG_13))
        assertFalse(AgeRatingTier.PG_13.allowsUpTo(AgeRatingTier.TV_14))
        assertFalse(AgeRatingTier.PG_13.allowsUpTo(AgeRatingTier.R))
        assertFalse(AgeRatingTier.G.allowsUpTo(AgeRatingTier.PG))
    }
}
