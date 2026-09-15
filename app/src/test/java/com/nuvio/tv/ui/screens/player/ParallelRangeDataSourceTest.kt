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
            ParallelRangeDataSource.isTailChunk(1389L, 1392L)
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
    fun `isChunkEvictionCandidate never evicts readerIdx or protectIndex`() {
        // Chunk is readerIdx
        assertEquals(
            false,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 5L,
                readerIdx = 5L,
                protectIndex = 0L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 0L,
                nowMs = 100_000L
            )
        )
        // Chunk is protectIndex
        assertEquals(
            false,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 5L,
                readerIdx = 10L,
                protectIndex = 5L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 0L,
                nowMs = 100_000L
            )
        )
    }

    @Test
    fun `isChunkEvictionCandidate protects tail chunks for slow mp4`() {
        // Total chunks = 100, tail is 96..99
        assertEquals(
            false,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 98L,
                readerIdx = 10L,
                protectIndex = 10L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 0L,
                nowMs = 100_000L
            )
        )
    }

    @Test
    fun `isChunkEvictionCandidate protects playhead window`() {
        // readerIdx = 10, backChunks = 2, prefetchWindow = 2 -> window is 8..12
        for (ci in 8L..12L) {
            assertEquals(
                false,
                ParallelRangeDataSource.isChunkEvictionCandidate(
                    chunkIndex = ci,
                    readerIdx = 10L,
                    protectIndex = -1L,
                    prefetchWindow = 2,
                    totalChunks = 100L,
                    lastTouchMs = 0L,
                    nowMs = 100_000L
                )
            )
        }
    }

    @Test
    fun `isChunkEvictionCandidate immediately evicts chunks behind playhead back window`() {
        // readerIdx = 10, backChunks = 2 -> chunks < 8 are behind playhead
        // Even if touched just 100ms ago (well within 15s touch guard), chunk 7 is evictable!
        assertEquals(
            true,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 7L,
                readerIdx = 10L,
                protectIndex = -1L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 99_900L,
                nowMs = 100_000L
            )
        )
        assertEquals(
            true,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 0L,
                readerIdx = 10L,
                protectIndex = -1L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 99_900L,
                nowMs = 100_000L
            )
        )
    }

    @Test
    fun `isChunkEvictionCandidate respects touch guard for ahead chunks`() {
        // readerIdx = 10, prefetch = 2 -> chunk 15 is far ahead
        // If touched recently (within 15s touch guard), not evictable yet
        assertEquals(
            false,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 15L,
                readerIdx = 10L,
                protectIndex = -1L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 95_000L,
                nowMs = 100_000L, // 5s ago < 15s guard
                touchGuardMs = 15_000L
            )
        )
        // If touched > 15s ago, evictable
        assertEquals(
            true,
            ParallelRangeDataSource.isChunkEvictionCandidate(
                chunkIndex = 15L,
                readerIdx = 10L,
                protectIndex = -1L,
                prefetchWindow = 2,
                totalChunks = 100L,
                lastTouchMs = 80_000L,
                nowMs = 100_000L, // 20s ago >= 15s guard
                touchGuardMs = 15_000L
            )
        )
    }
}

