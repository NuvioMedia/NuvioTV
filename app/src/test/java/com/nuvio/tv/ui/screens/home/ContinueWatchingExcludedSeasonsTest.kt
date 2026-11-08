package com.nuvio.tv.ui.screens.home

import com.nuvio.tv.domain.model.WatchProgress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class ContinueWatchingExcludedSeasonsTest {
    @Test
    fun `next up skips excluded seasons and retains the history seed`() {
        val seed = seed().copy(excludedNextUpSeasons = setOf(2))
        val next = resolveNextUpVideoFromMeta(seed, metadata(), showUnairedNextUp = false)
        assertEquals(3, next?.season)
        assertEquals(1, next?.episode)
        assertEquals(1, seed.season)
    }

    @Test
    fun `excluding the seed season still allows an eligible following season`() {
        val next = resolveNextUpVideoFromMeta(seed().copy(excludedNextUpSeasons = setOf(1)), metadata(), false)
        assertEquals(2, next?.season)
    }

    @Test
    fun `providers without exclusions preserve existing next up behavior`() {
        val next = resolveNextUpVideoFromMeta(seed(), metadata(), false)
        assertEquals(2, next?.season)
        assertEquals(1, next?.episode)
    }

    @Test
    fun `changing exclusions invalidates cached resolution with deterministic keys`() {
        val original = buildNextUpSeedCacheKey(seed(), false)
        val changed = buildNextUpSeedCacheKey(seed().copy(excludedNextUpSeasons = setOf(2, 3)), false)
        val reordered = buildNextUpSeedCacheKey(seed().copy(excludedNextUpSeasons = setOf(3, 2)), false)
        assertNotEquals(original, changed)
        assertEquals(changed, reordered)
    }

    private fun seed() = WatchProgress(
        "tt1", "series", "Series", null, null, null, "tt1:1:1", 1, 1, null,
        100, 100, 1, progressPercent = 100f
    )
    private fun metadata() = CwMetaSummary(
        "tt1", "Series", null, null, null, null, emptyList(), null, null, null, null,
        (1..3).map { season -> CwVideoSummary("tt1:$season:1", "Episode", "2020-01-01T00:00:00Z", null, season, 1, null) }
    )
}
