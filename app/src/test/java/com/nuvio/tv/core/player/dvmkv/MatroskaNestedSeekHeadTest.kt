package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaNestedSeekHeadTest {

    @Test
    fun `first SeekHead that only names another SeekHead is followed`() {
        val pendingTrailingSeekHead = 2_837_202_547L
        assertTrue(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                true,
                UNSET,
                pendingTrailingSeekHead,
                0,
            ),
        )
    }

    @Test
    fun `direct Cues pointer is preferred over a nested SeekHead`() {
        assertFalse(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                true,
                2_837_048_772L,
                2_837_202_547L,
                0,
            ),
        )
    }

    @Test
    fun `missing Cues and missing trailing SeekHead is not followed`() {
        assertFalse(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                true,
                UNSET,
                UNSET,
                0,
            ),
        )
    }

    @Test
    fun `nested SeekHead hop is capped`() {
        assertFalse(
            MatroskaExtractor.shouldFollowNestedSeekHead(
                true,
                UNSET,
                100L,
                4,
            ),
        )
    }

    @Test
    fun `records trailing SeekHead and ignores the already-parsed first one`() {
        val firstSeekHead = 52L
        val cluster = 5_843L
        val trailingSeekHead = 2_837_202_547L

        val afterFirst =
            MatroskaExtractor.nextNestedSeekHeadPosition(
                trailingSeekHead,
                UNSET,
                UNSET,
                UNSET,
            )
        assertEquals(trailingSeekHead, afterFirst)

        val afterTrailingPointsBack =
            MatroskaExtractor.nextNestedSeekHeadPosition(
                firstSeekHead,
                UNSET,
                trailingSeekHead,
                cluster,
            )
        assertEquals(trailingSeekHead, afterTrailingPointsBack)
    }

    companion object {
        private val UNSET = C.INDEX_UNSET.toLong()
    }
}
