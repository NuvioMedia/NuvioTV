package com.nuvio.tv.ui.screens.player

import androidx.media3.common.Metadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.metadata.id3.ChapterFrame
import androidx.media3.extractor.metadata.id3.TextInformationFrame
import com.nuvio.tv.core.player.metadata.TimedMetadataBody
import com.nuvio.tv.core.player.metadata.TimedMetadataEvent
import com.nuvio.tv.core.player.metadata.TimedMetadataProvenance
import com.nuvio.tv.core.player.metadata.TimedMetadataScope
import com.nuvio.tv.core.player.metadata.TimedMetadataTimeline

/** Accumulates only chapter frames actually emitted by the active Media3 source. */
@OptIn(UnstableApi::class)
internal fun mergeMedia3ChapterMetadata(
    current: TimedMetadataTimeline?,
    metadata: Metadata,
    scope: TimedMetadataScope,
    durationMs: Long?
): TimedMetadataTimeline? {
    val previousEvents = current?.takeIf { it.scope == scope }?.events.orEmpty()
    val eventsById = previousEvents.associateByTo(linkedMapOf()) { it.id }

    for (index in 0 until metadata.length()) {
        val chapter = metadata[index] as? ChapterFrame ?: continue
        val startMs = chapter.startTimeMs.toLong()
        if (startMs < 0L) continue

        val chapterId = chapter.chapterId.trim().take(256)
        val title = (0 until chapter.subFrameCount)
            .asSequence()
            .mapNotNull { chapter.getSubFrame(it) as? TextInformationFrame }
            .firstOrNull { it.id == ID3_TITLE_FRAME }
            ?.values
            ?.firstOrNull()
            ?.trim()
            ?.takeIf(String::isNotBlank)
            ?: chapterId.takeIf(String::isNotBlank)
            ?: continue
        val endMs = chapter.endTimeMs.toLong().takeIf { it > startMs }
        val id = "id3:${chapterId.ifBlank { startMs.toString() }}:$startMs".take(1024)
        eventsById[id] = TimedMetadataEvent(
            id = id,
            startMs = startMs,
            endMs = endMs,
            body = TimedMetadataBody.Chapter(title.take(256)),
            provenance = TimedMetadataProvenance(
                providerId = MEDIA3_ID3_PROVIDER,
                evidenceId = chapterId.takeIf(String::isNotBlank)
            )
        )
    }

    if (eventsById.isEmpty()) return null
    return TimedMetadataTimeline.create(
        scope = scope,
        input = eventsById.values,
        durationMs = durationMs?.takeIf { it > 0L },
        limit = TimedMetadataTimeline.MAX_EVENTS
    )
}

internal const val MEDIA3_ID3_PROVIDER = "media3-id3"
private const val ID3_TITLE_FRAME = "TIT2"
