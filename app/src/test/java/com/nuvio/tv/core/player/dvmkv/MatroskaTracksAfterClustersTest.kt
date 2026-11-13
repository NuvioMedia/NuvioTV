package com.nuvio.tv.core.player.dvmkv

import androidx.media3.common.C
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MatroskaTracksAfterClustersTest {

    @Test
    fun `tracks the SeekHead places after the cluster are read first`() {
        assertTrue(MatroskaExtractor.shouldSeekForTracksBeforeCluster(0, 9_000L, 300L, false))
    }

    @Test
    fun `known tracks never trigger the detour`() {
        assertFalse(MatroskaExtractor.shouldSeekForTracksBeforeCluster(2, 9_000L, 300L, false))
    }

    @Test
    fun `tracks in front or at an unknown position never trigger the detour`() {
        assertFalse(MatroskaExtractor.shouldSeekForTracksBeforeCluster(0, 120L, 300L, false))
        assertFalse(MatroskaExtractor.shouldSeekForTracksBeforeCluster(0, C.INDEX_UNSET.toLong(), 300L, false))
    }

    @Test
    fun `the detour runs once`() {
        assertFalse(MatroskaExtractor.shouldSeekForTracksBeforeCluster(0, 9_000L, 300L, true))
    }
}
