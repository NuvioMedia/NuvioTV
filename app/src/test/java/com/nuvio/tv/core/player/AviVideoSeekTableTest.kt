package com.nuvio.tv.core.player

import androidx.media3.extractor.SeekPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviVideoSeekTableTest {

    // 25 fps XviD, key frames at frames 0, 4342 and 4407 as in the sample around 176 s.
    private val video = AviHeader.Stream(0, "vids", 1, 25, 178_944, 0)
    private val keyFrames = AviIndex.KeyFrames(intArrayOf(0, 4342, 4407), longArrayOf(10_268, 5_000_000, 5_100_000))
    private val table = AviVideoSeekTable.create(video, keyFrames)!!

    @Test
    fun aKeyFrameTimeGivesThatKeyFrameAlone() {
        val points = table.seekPoints(176_280_000)
        assertEquals(SeekPoint(176_280_000, 5_100_000), points.first)
        assertEquals(points.first, points.second)
    }

    @Test
    fun aTimeBetweenKeyFramesGivesBothNeighbours() {
        val points = table.seekPoints(176_249_000)
        assertEquals(SeekPoint(173_680_000, 5_000_000), points.first)
        assertEquals(SeekPoint(176_280_000, 5_100_000), points.second)
        assertEquals(SeekPoint(176_280_000, 5_100_000), table.seekPoints(200_000_000).first)
    }

    @Test
    fun onlyKeyFramesAndPlacesBeforeTheFirstAreSafeStarts() {
        assertTrue(table.isSafeStart(5_100_000))
        assertTrue(table.isSafeStart(10_252))
        // The audio chunk stored just before the key frame at 176.28 s, which the stock seek map picks.
        assertFalse(table.isSafeStart(5_099_000))
    }

    @Test
    fun unusableHeadersOrKeyFramesGiveNoTable() {
        assertNull(AviVideoSeekTable.create(video.copy(rate = 0), keyFrames))
        assertNull(AviVideoSeekTable.create(video.copy(length = 0), keyFrames))
        assertNull(AviVideoSeekTable.create(video, AviIndex.KeyFrames(intArrayOf(5, 2), longArrayOf(1, 2))))
        assertNull(AviVideoSeekTable.create(video, AviIndex.KeyFrames(IntArray(0), LongArray(0))))
    }

    @Test
    fun keyFramesAreNumberedAmongAllChunksOfTheVideoStream() {
        val entries = listOf(
            Triple("01wb", 0x10, 10_252L),
            Triple("00dc", 0x10, 18_324L),
            Triple("00db", 0, 19_248L),
            Triple("01wb", 0x10, 19_748L),
            Triple("00dc", 0, 20_248L),
            Triple("00dc", 0x10, 21_248L),
        )
        val index = AviIndex.Chunks(
            entries.map { (id) -> id[0].code or (id[1].code shl 8) or (id[2].code shl 16) or (id[3].code shl 24) }
                .toIntArray(),
            entries.map { it.second }.toIntArray(),
            entries.map { it.third }.toLongArray(),
            IntArray(entries.size) { 100 },
            entries.size,
        )
        val frames = AviIndex.videoKeyFrames(index, 0)!!
        assertEquals(listOf(0, 3), frames.chunkIndices.toList())
        assertEquals(listOf(18_324L, 21_248L), frames.positions.toList())
        assertNull(AviIndex.videoKeyFrames(index, 2))
    }
}
