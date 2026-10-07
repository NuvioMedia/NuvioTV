package com.nuvio.tv.ui.screens.player.seekpreview

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * A preview frame with the cue window it stands for.
 *
 * Cue times are on the preview timeline: the position asked for plus the track's [SeekPreviewTrack.offsetMs].
 * [approximate] marks a stand-in from a nearby moment (an on-device frame for a slot that has
 * none yet); its [bitmap] is already a low-resolution copy, so drawing it scaled up reads as
 * blurred on every API level without a blur shader.
 */
data class SeekPreviewThumbnail(
    val bitmap: Bitmap,
    val cueStartMs: Long,
    val cueEndMs: Long,
    val approximate: Boolean = false,
)

/**
 * Seek-preview thumbnails queried by playback position, generated on the device from the playing stream.
 */
interface SeekPreviewTrack {
    /** Signed milliseconds added to a requested position before the lookup. */
    var offsetMs: Long

    /** Built from the playing stream itself, so it never needs Preview Sync. */
    val isLocal: Boolean get() = false

    /** Bumped whenever thumbnails are added, so a visible preview re-reads its frame. */
    val revision: StateFlow<Int> get() = StaticRevision

    /** The thumbnail covering [positionMs] (after [offsetMs]) with its cue window, or null. */
    suspend fun thumbnailFor(positionMs: Long): SeekPreviewThumbnail?

    /**
     * Like [thumbnailFor], for a frame shown beside the one being scrubbed to: it does not steer
     * background work (the on-device track decodes nearest the last [thumbnailFor] first).
     */
    suspend fun sideThumbnailFor(positionMs: Long): SeekPreviewThumbnail? = thumbnailFor(positionMs)

    /** Stops background work; the track is not fed again. */
    fun close() = Unit
}

private val StaticRevision: StateFlow<Int> = MutableStateFlow(0)
