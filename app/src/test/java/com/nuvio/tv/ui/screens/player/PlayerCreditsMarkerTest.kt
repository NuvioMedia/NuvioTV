package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.repository.SkipInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayerCreditsMarkerTest {
    private val durationMs = 3_000_000L

    @Test
    fun `earliest credits or ending start is used`() {
        val intervals = listOf(
            SkipInterval(60.0, 120.0, "intro", "introdb"),
            SkipInterval(2_850.0, 3_000.0, "movie-credits", "introdb"),
            SkipInterval(2_820.0, 2_900.0, "outro", "chapters")
        )

        assertEquals(94.0, creditsStartPercent(intervals, durationMs)!!, 0.001)
    }

    @Test
    fun `post-credits scenes and intros are not credits`() {
        val intervals = listOf(
            SkipInterval(60.0, 120.0, "intro", "introdb"),
            SkipInterval(2_950.0, 2_990.0, "post-credits", "introdb")
        )

        assertNull(creditsStartPercent(intervals, durationMs))
    }

    @Test
    fun `no duration means no marker`() {
        assertNull(creditsStartPercent(listOf(SkipInterval(2_850.0, 3_000.0, "ed", "aniskip")), 0L))
    }
}
