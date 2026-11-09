package com.nuvio.tv.core.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AviAudioClockTest {

    // The MP3 stream of the XviD sample: 128 kbit/s, a 504 ms first chunk, then 48, 48 and 24 ms chunks.
    private val mp3Stream = AviHeader.Stream(
        number = 1, type = "auds", scale = 384, rate = 16_000, length = 298_226, sampleSize = 384,
        avgBytesPerSecond = 16_000,
    )
    private val mp3Sizes = IntArray(178_924) { i -> if (i == 0) 8064 else if (i % 3 == 0) 384 else 768 }
    private val mp3DurationUs = 298_226L * 384 * 1_000_000 / 16_000

    private fun positions(sizes: IntArray, first: Long = 10_260L): LongArray {
        var at = first
        return LongArray(sizes.size) { i -> at.also { at += sizes[i] + 8 + 1_000 } }
    }

    private fun stockTimeUs(index: Int) = mp3DurationUs * index / mp3Sizes.size

    private fun mp3Clock(): Pair<AviAudioClock, LongArray> {
        val clock = assertNotNullAndGet(AviAudioClock.forStream(mp3Stream))
        val positions = positions(mp3Sizes)
        assertTrue(clock.useIndex(AviIndex.Entries(positions, mp3Sizes)))
        return clock to positions
    }

    private fun <T> assertNotNullAndGet(value: T?): T {
        assertNotNull(value)
        return value!!
    }

    @Test
    fun timesChunksByTheBytesBeforeThem() {
        val (clock, positions) = mp3Clock()
        assertTrue(clock.countsBytes)
        assertEquals(0L, clock.chunkTimeUs(positions[0], 8064, stockTimeUs(0)))
        assertEquals(504_000L, clock.chunkTimeUs(positions[1], 768, stockTimeUs(1)))
        assertEquals(552_000L, clock.chunkTimeUs(positions[2], 768, stockTimeUs(2)))
        assertEquals(600_000L, clock.chunkTimeUs(positions[3], 384, stockTimeUs(3)))
        assertEquals(40_002L, stockTimeUs(1))
    }

    @Test
    fun theStockTimeIsAboutHalfASecondEarlyThroughTheFile() {
        val (clock, positions) = mp3Clock()
        // Chunk 1501 starts at 60.504 s; the stock reader says 60.044 s, and only 0.016 s early at the end.
        assertEquals(60_504_000L, clock.chunkTimeUs(positions[1501], 768, stockTimeUs(1501)))
        assertEquals(-460L, (stockTimeUs(1501) - 60_504_000L) / 1_000)
        val last = mp3Sizes.size - 1
        assertEquals(mp3DurationUs - 24_000L, clock.chunkTimeUs(positions[last], 384, stockTimeUs(last)))
        assertTrue(mp3DurationUs - 24_000L - stockTimeUs(last) in 0L..30_000L)
    }

    @Test
    fun afterASeekTheChunkIsFoundByItsPositionNotByTheReadersNumber() {
        val (clock, positions) = mp3Clock()
        clock.chunkTimeUs(positions[0], 8064, 0L)
        clock.reset()
        // The reader numbers the first chunk after the seek one too low.
        assertEquals(60_504_000L, clock.chunkTimeUs(positions[1501], 768, stockTimeUs(1500)))
        assertTrue(clock.lastChunkIndexed)
        assertEquals(60_552_000L, clock.chunkTimeUs(positions[1502], 768, stockTimeUs(1501)))
    }

    @Test
    fun aChunkTheIndexDoesNotListFollowsOnFromTheOneBefore() {
        val clock = assertNotNullAndGet(AviAudioClock.forStream(mp3Stream))
        val sizes = mp3Sizes.copyOf(3)
        val positions = positions(sizes)
        clock.useIndex(AviIndex.Entries(positions, sizes))
        clock.chunkTimeUs(positions[2], 768, 0L)
        assertEquals(600_000L, clock.chunkTimeUs(positions[2] + 2_000, 384, 999L))
        assertFalse(clock.lastChunkIndexed)
        assertEquals(624_000L, clock.chunkTimeUs(-1L, 384, 999L))
    }

    @Test
    fun withoutAnIndexTheClockStartsFromTheReadersTime() {
        val clock = assertNotNullAndGet(AviAudioClock.forStream(mp3Stream))
        assertEquals(0, clock.indexedChunks)
        assertEquals(0L, clock.chunkTimeUs(10_260L, 8064, 0L))
        assertEquals(504_000L, clock.chunkTimeUs(20_000L, 768, 40_002L))
        clock.reset()
        assertEquals(1_000_000L, clock.chunkTimeUs(30_000L, 768, 1_000_000L))
        assertEquals(1_048_000L, clock.chunkTimeUs(31_000L, 768, 1_040_000L))
    }

    @Test
    fun ac3WithByteSamplesUsesTheByteRate() {
        // 448 kbit/s AC-3 as DVD rips write it: scale 1, rate 56000, sample size 1.
        val stream = AviHeader.Stream(1, "auds", 1, 56_000, 373_382_912, 1, 56_000)
        val clock = assertNotNullAndGet(AviAudioClock.forStream(stream))
        val sizes = IntArray(4) { if (it == 0) 26_880 else 3_584 }
        val positions = positions(sizes)
        clock.useIndex(AviIndex.Entries(positions, sizes))
        assertEquals(480_000L, clock.chunkTimeUs(positions[1], 3_584, 64_000L))
        assertEquals(544_000L, clock.chunkTimeUs(positions[2], 3_584, 128_000L))
    }

    @Test
    fun streamsWithoutASampleSizeCountOneBlockPerChunk() {
        val stream = AviHeader.Stream(1, "auds", 1152, 48_000, 1000, 0, 16_000)
        val clock = assertNotNullAndGet(AviAudioClock.forStream(stream))
        assertFalse(clock.countsBytes)
        val sizes = IntArray(10) { 417 }
        val positions = positions(sizes)
        clock.useIndex(AviIndex.Entries(positions, sizes))
        assertEquals(168_000L, clock.chunkTimeUs(positions[7], 417, 144_000L))
        assertEquals(192_000L, clock.chunkTimeUs(-1L, 417, 0L))
    }

    @Test
    fun unusableHeadersGiveNoClock() {
        assertNull(AviAudioClock.forStream(mp3Stream.copy(type = "vids")))
        assertNull(AviAudioClock.forStream(mp3Stream.copy(scale = 0, rate = 0, avgBytesPerSecond = 0)))
        assertNull(AviAudioClock.forStream(mp3Stream.copy(avgBytesPerSecond = 24_000)))
        assertNull(AviAudioClock.forStream(mp3Stream.copy(sampleSize = 0, scale = 1, rate = 48_000)))
        assertNotNull(AviAudioClock.forStream(mp3Stream.copy(scale = 0, rate = 0)))
    }

    @Test
    fun anIndexOutOfFileOrderIsNotUsed() {
        val clock = assertNotNullAndGet(AviAudioClock.forStream(mp3Stream))
        assertFalse(clock.useIndex(AviIndex.Entries(longArrayOf(100, 90), intArrayOf(768, 768))))
        assertEquals(0, clock.indexedChunks)
    }

    @Test
    fun scaleKeepsLargeValuesExact() {
        assertEquals(7_157_424_000_000L / 1_000, AviAudioClock.scale(114_518_784L, 125, 2))
        assertEquals(3_000_000_000_000L, AviAudioClock.scale(3_000_000_000_000L, 1_000_000, 1_000_000))
    }

    private fun chunks(vararg entries: Triple<String, Long, Int>): AviIndex.Chunks {
        val ids = entries.map { (id) ->
            id[0].code or (id[1].code shl 8) or (id[2].code shl 16) or (id[3].code shl 24)
        }
        return AviIndex.Chunks(
            ids.toIntArray(),
            IntArray(entries.size) { 0x10 },
            LongArray(entries.size) { entries[it].second },
            IntArray(entries.size) { entries[it].third },
            entries.size,
        )
    }

    @Test
    fun audioEntriesStartAfterTheChunkHeader() {
        val index = chunks(
            Triple("01wb", 10_252L, 8064),
            Triple("00dc", 18_324L, 5000),
            Triple("01wb", 23_332L, 768),
            Triple("02wb", 24_108L, 400),
        )
        val entries = AviIndex.audioEntries(index, listOf(1))
        assertEquals(setOf(1), entries.keys)
        val stream = entries.getValue(1)
        assertEquals(listOf(10_260L, 23_340L), stream.dataPositions.toList())
        assertEquals(listOf(8064, 768), stream.sizes.toList())
        assertTrue(AviIndex.audioEntries(index, emptyList()).isEmpty())
    }
}
