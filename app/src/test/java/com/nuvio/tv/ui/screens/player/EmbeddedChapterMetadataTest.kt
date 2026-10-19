package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import com.nuvio.tv.core.player.metadata.TimedMetadataBody
import com.nuvio.tv.core.player.metadata.TimedMetadataScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(UnstableApi::class)
class EmbeddedChapterMetadataTest {
    private val scope = TimedMetadataScope("movie-1", "movie", "movie-1")

    @Test
    fun extractsNamedChaptersWithSourceProvenanceAndTimes() {
        val result = mergeMedia3ChapterMetadata(
            current = null,
            metadata = Metadata(
                ChapterFrame("opening", 0, 45_000, -1, -1, emptyArray()),
                ChapterFrame("finale", 45_000, 90_000, -1, -1, emptyArray())
            ),
            scope = scope,
            durationMs = 100_000
        )

        assertEquals(listOf("opening", "finale"), result?.events?.map { (it.body as TimedMetadataBody.Chapter).title })
        assertEquals(listOf(0L, 45_000L), result?.events?.map { it.startMs })
        assertEquals(listOf("media3-id3", "media3-id3"), result?.events?.map { it.provenance.providerId })
    }

    @Test
    fun accumulatesFramesEmittedAcrossPlaybackMetadataCallbacks() {
        val first = mergeMedia3ChapterMetadata(
            current = null,
            metadata = Metadata(ChapterFrame("one", 0, 10_000, -1, -1, emptyArray())),
            scope = scope,
            durationMs = 30_000
        )

        val combined = mergeMedia3ChapterMetadata(
            current = first,
            metadata = Metadata(ChapterFrame("two", 10_000, 20_000, -1, -1, emptyArray())),
            scope = scope,
            durationMs = 30_000
        )

        assertEquals(listOf("one", "two"), combined?.events?.map { (it.body as TimedMetadataBody.Chapter).title })
    }

    @Test
    fun ignoresInvalidTimesAndFramesOutsideKnownDuration() {
        val result = mergeMedia3ChapterMetadata(
            current = null,
            metadata = Metadata(
                ChapterFrame("negative", -1, 5_000, -1, -1, emptyArray()),
                ChapterFrame("zero-length", 5_000, 5_000, -1, -1, emptyArray()),
                ChapterFrame("past-end", 20_000, 25_000, -1, -1, emptyArray())
            ),
            scope = scope,
            durationMs = 20_000
        )

        assertNull(result)
    }

    @Test
    fun resetsEventsWhenPlaybackIdentityChanges() {
        val otherScope = scope.copy(videoId = "episode-2")
        val current = mergeMedia3ChapterMetadata(
            current = mergeMedia3ChapterMetadata(
                null,
                Metadata(ChapterFrame("old", 0, 5_000, -1, -1, emptyArray())),
                scope,
                30_000
            ),
            metadata = Metadata(ChapterFrame("new", 6_000, 9_000, -1, -1, emptyArray())),
            scope = otherScope,
            durationMs = 30_000
        )

        assertTrue(current?.events?.all { it.id.contains("new") } == true)
    }
}
