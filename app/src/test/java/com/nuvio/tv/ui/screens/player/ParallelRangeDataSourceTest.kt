package com.nuvio.tv.ui.screens.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

class ParallelRangeDataSourceTest {

    @Test
    fun `parseRetryAfterHeaderMs reads delta-seconds`() {
        assertEquals(30_000L, ParallelRangeRetryAfter.parseHeaderMs("30"))
        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs("0"))
        assertEquals(1_000L, ParallelRangeRetryAfter.parseHeaderMs("1"))
    }

    @Test
    fun `parseRetryAfterHeaderMs reads RFC1123 HTTP-date`() {
        val now = 1_700_000_000_000L // fixed epoch for determinism
        val target = Instant.ofEpochMilli(now + 45_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(target)

        assertEquals(45_000L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }

    @Test
    fun `parseRetryAfterHeaderMs returns null for missing or garbage`() {
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(null))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs(""))
        assertNull(ParallelRangeRetryAfter.parseHeaderMs("not-a-date"))
    }

    @Test
    fun `parseRetryAfterHeaderMs clamps past HTTP-date to zero`() {
        val now = 1_700_000_000_000L
        val past = Instant.ofEpochMilli(now - 60_000L)
        val header = DateTimeFormatter.RFC_1123_DATE_TIME
            .withLocale(Locale.US)
            .withZone(ZoneOffset.UTC)
            .format(past)

        assertEquals(0L, ParallelRangeRetryAfter.parseHeaderMs(header, nowEpochMs = now))
    }

    @Test
    fun `lookahead stays at current chunk until sequential run and current chunk complete`() {
        assertEquals(
            1,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 0L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = false,
                nextChunkComplete = false,
                configuredDepth = 4,
                rateLimitDepth = 4
            )
        )
        assertEquals(
            2,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 1L * 1024L * 1024L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = false,
                nextChunkComplete = false,
                configuredDepth = 4,
                rateLimitDepth = 4
            )
        )
        assertEquals(
            1,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 512L * 1024L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = true,
                nextChunkComplete = true,
                configuredDepth = 4,
                rateLimitDepth = 4
            )
        )
    }

    @Test
    fun `lookahead uses configured depth after current and next chunks are complete`() {
        assertEquals(
            2,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 1L * 1024L * 1024L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = true,
                nextChunkComplete = false,
                configuredDepth = 4,
                rateLimitDepth = 4
            )
        )
        assertEquals(
            4,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 1L * 1024L * 1024L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = true,
                nextChunkComplete = true,
                configuredDepth = 4,
                rateLimitDepth = 4
            )
        )
        assertEquals(
            2,
            ParallelRangeDataSource.lookaheadDepth(
                bytesServedThisOpen = 1L * 1024L * 1024L,
                earnedPrefetchBytes = 1L * 1024L * 1024L,
                currentChunkComplete = true,
                nextChunkComplete = true,
                configuredDepth = 4,
                rateLimitDepth = 2
            )
        )
    }

    @Test
    fun `side cursor does not move main read cursor`() {
        assertEquals(
            false,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = -1L,
                chunkIndex = 1389L,
                prefetchWindow = 4,
                sequentialOpen = false,
                currentChunkComplete = false,
                totalChunks = 1390L
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = -1L,
                chunkIndex = 35L,
                prefetchWindow = 4,
                sequentialOpen = false,
                currentChunkComplete = false,
                totalChunks = 1390L
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.isTailChunk(1391L, 1392L)
        )
        assertEquals(
            true,
            ParallelRangeDataSource.isTailChunk(1388L, 1392L)
        )
        assertEquals(
            false,
            ParallelRangeDataSource.isTailChunk(1387L, 1392L)
        )
        assertEquals(
            false,
            ParallelRangeDataSource.isTailChunk(105L, 1392L)
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = 35L,
                chunkIndex = 36L,
                prefetchWindow = 4,
                sequentialOpen = false,
                currentChunkComplete = false,
                totalChunks = 1390L
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = 35L,
                chunkIndex = 1389L,
                prefetchWindow = 4,
                sequentialOpen = false,
                currentChunkComplete = false,
                totalChunks = 1390L
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = 35L,
                chunkIndex = 1389L,
                prefetchWindow = 4,
                sequentialOpen = true,
                currentChunkComplete = true,
                totalChunks = 1390L
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = 35L,
                chunkIndex = 80L,
                prefetchWindow = 4,
                sequentialOpen = true,
                currentChunkComplete = false,
                totalChunks = 1390L
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldMoveMainCursor(
                lastReadChunkIndex = 35L,
                chunkIndex = 80L,
                prefetchWindow = 4,
                sequentialOpen = true,
                currentChunkComplete = true,
                totalChunks = 1390L
            )
        )
    }

    @Test
    fun `playhead window keeps two chunks behind the reader`() {
        assertEquals(
            true,
            ParallelRangeDataSource.isInPlayheadWindow(
                readerIdx = 253L,
                chunkIndex = 251L,
                prefetchWindow = 4
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.isInPlayheadWindow(
                readerIdx = 255L,
                chunkIndex = 255L,
                prefetchWindow = 4
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.isInPlayheadWindow(
                readerIdx = 251L,
                chunkIndex = 255L,
                prefetchWindow = 4
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.isInPlayheadWindow(
                readerIdx = 255L,
                chunkIndex = 251L,
                prefetchWindow = 4
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.isInPlayheadWindow(
                readerIdx = -1L,
                chunkIndex = 251L,
                prefetchWindow = 4
            )
        )
    }

    @Test
    fun `releaseTailChunks is safe when no session is active`() {
        ParallelRangeDataSource.releaseTailChunks()
        ParallelRangeDataSource.releaseTailChunks(moovOffset = -1L, moovSize = 0L)
    }

    @Test
    fun `tail chunk boundaries with TAIL_CHUNK_COUNT 4`() {
        val totalChunks = 100L
        assertEquals(false, ParallelRangeDataSource.isTailChunk(95L, totalChunks))
        assertEquals(true, ParallelRangeDataSource.isTailChunk(96L, totalChunks))
        assertEquals(true, ParallelRangeDataSource.isTailChunk(97L, totalChunks))
        assertEquals(true, ParallelRangeDataSource.isTailChunk(98L, totalChunks))
        assertEquals(true, ParallelRangeDataSource.isTailChunk(99L, totalChunks))
        assertEquals(true, ParallelRangeDataSource.isTailChunk(100L, totalChunks))
        assertEquals(false, ParallelRangeDataSource.isTailChunk(99L, 0L))
        assertEquals(false, ParallelRangeDataSource.isTailChunk(99L, -1L))
    }

    @Test
    fun `moov eviction range drops only full-overlap chunks`() {
        val chunk = 16L * 1024L * 1024L
        assertEquals(LongRange.EMPTY, ParallelRangeDataSource.moovEvictionRange(-1L, 100L, chunk))
        assertEquals(LongRange.EMPTY, ParallelRangeDataSource.moovEvictionRange(0L, 0L, chunk))
        // moov starts mid-chunk 0 and fits in chunk 0: keep the mdat prefix, evict nothing
        assertEquals(LongRange.EMPTY, ParallelRangeDataSource.moovEvictionRange(1_000L, 20L, chunk))
        // aligned 2-chunk moov at chunk 10-11
        val aligned = 10L * chunk
        assertEquals(10L until 12L, ParallelRangeDataSource.moovEvictionRange(aligned, chunk * 2L, chunk))
        // starts 1 byte into chunk 10, spans into chunk 12: skip partial first chunk
        assertEquals(11L until 13L, ParallelRangeDataSource.moovEvictionRange(aligned + 1L, chunk * 2L, chunk))
    }

    @Test
    fun `prefetch still schedules playhead after moov release`() {
        val moovRange = 96L until 100L
        assertEquals(
            true,
            ParallelRangeDataSource.shouldPrefetchChunk(
                chunkIndex = 98L,
                currentChunkIdx = 98L,
                prefetchWindow = 4,
                tailReleased = true,
                moovChunkRange = moovRange
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldPrefetchChunk(
                chunkIndex = 99L,
                currentChunkIdx = 97L,
                prefetchWindow = 4,
                tailReleased = true,
                moovChunkRange = moovRange
            )
        )
        assertEquals(
            false,
            ParallelRangeDataSource.shouldPrefetchChunk(
                chunkIndex = 98L,
                currentChunkIdx = 10L,
                prefetchWindow = 4,
                tailReleased = true,
                moovChunkRange = moovRange
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldPrefetchChunk(
                chunkIndex = 50L,
                currentChunkIdx = 10L,
                prefetchWindow = 4,
                tailReleased = true,
                moovChunkRange = moovRange
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.shouldPrefetchChunk(
                chunkIndex = 98L,
                currentChunkIdx = 10L,
                prefetchWindow = 4,
                tailReleased = false,
                moovChunkRange = moovRange
            )
        )
    }
}
