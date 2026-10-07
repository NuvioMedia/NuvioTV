@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.seekpreview.local

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.extractor.ChunkIndex
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.mp4.Mp4Extractor
import kotlin.math.abs
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The ExoPlayer stream a player is showing, as far as on-device previews need it. */
internal class LocalPreviewSource(
    val owner: Any,
    val sourceKey: String,
    val context: Context,
) {
    @Volatile var released = false
    @Volatile var track: LocalPreviewTrack? = null

    /**
     * The stream's index when it lists real keyframes: an MP4's sync-sample table, or the cue
     * points of an MKV (or a fragmented MP4's segment index). Estimated maps (constant bitrate,
     * binary search) would point between keyframes, so they are not kept.
     */
    @Volatile var keyframeIndex: SeekMap? = null

    /**
     * The keyframe nearest [positionMs] when one is within [toleranceMs], from the stream's own
     * index; null without a usable index.
     */
    fun keyframeNear(positionMs: Long, toleranceMs: Long): Long? {
        val seekMap = keyframeIndex ?: return null
        if (released || positionMs < 0L) return null
        return runCatching {
            val points = seekMap.getSeekPoints(positionMs * 1_000L)
            listOf(points.first.timeUs, points.second.timeUs)
                .map { it / 1_000L }
                .filter { it >= 0L && abs(it - positionMs) <= toleranceMs }
                .minByOrNull { abs(it - positionMs) }
        }.getOrNull()
    }
}

/**
 * Connects the player to on-device seek previews. The player makes two calls: [register] when
 * it builds an ExoPlayer for a stream (the returned factory copies playback's own keyframes to
 * the preview track) and [unregister] when it releases it. [SeekPreviewState] watches
 * [sourceFor] and [open]s a track for the registered stream. The MPV engine never registers, so
 * it gets no on-device track.
 */
internal object LocalPreviewSources {
    private val current = MutableStateFlow<LocalPreviewSource?>(null)

    /**
     * Registers [sourceKey] as [owner]'s playing stream and returns [factory] wrapped so its
     * video keyframes reach that stream's preview track while playback demuxes them.
     */
    fun register(owner: Any, context: Context, sourceKey: String, factory: ExtractorsFactory): ExtractorsFactory {
        val source = LocalPreviewSource(owner, sourceKey, context.applicationContext)
        current.value?.let { stale ->
            stale.released = true
            stale.track = null
        }
        current.value = source
        return VideoKeyframeExtractorsFactory(factory, object : KeyframeSink {
            private fun track() = source.takeIf { !it.released }?.track

            override fun wantsKeyframes(): Boolean = track()?.wantsKeyframes() == true

            override fun wantsKeyframe(timeUs: Long): Boolean = track()?.wantsKeyframe(timeUs) == true

            // No track yet: say yes, so the tap only stops copying once a track says nothing is due.
            override fun mayWantKeyframesNear(timeUs: Long): Boolean = track()?.mayWantKeyframesNear(timeUs) ?: true

            override fun onKeyframe(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                track()?.onKeyframe(format, timeUs, data, offset, size)
            }

            override fun onSeekMap(seekMap: SeekMap) {
                if (seekMap is Mp4Extractor || seekMap is ChunkIndex) source.keyframeIndex = seekMap
            }
        })
    }

    /** [owner] released its player; its stream stops feeding previews. */
    fun unregister(owner: Any) {
        val source = current.value?.takeIf { it.owner === owner } ?: return
        source.released = true
        source.track = null
        current.compareAndSet(source, null)
    }

    /** The stream [owner] is playing with ExoPlayer, or null. */
    fun sourceFor(owner: Any): Flow<LocalPreviewSource?> =
        current.map { source -> source?.takeIf { it.owner === owner } }.distinctUntilChanged()

    /**
     * A started track fed by [source]'s keyframes, or null when [source] is no longer playing.
     * The caller closes it.
     */
    fun open(source: LocalPreviewSource, cacheKey: String, durationMs: Long): LocalPreviewTrack? {
        if (source.released || durationMs <= 0L) return null
        val track = LocalPreviewTrack(source.context, cacheKey, durationMs, source::keyframeNear)
        source.track = track
        track.start()
        return track
    }

    /** Detaches [track] from [source] (if still attached) and closes it. */
    fun close(source: LocalPreviewSource, track: LocalPreviewTrack) {
        if (source.track === track) source.track = null
        track.close()
    }
}

/** Names a title and release for the thumbnail cache; the duration tells releases apart. */
internal fun localSeekPreviewCacheKey(
    contentId: String?,
    season: Int?,
    episode: Int?,
    durationMs: Long,
): String = listOf(
    contentId.orEmpty(),
    season?.toString().orEmpty(),
    episode?.toString().orEmpty(),
    (durationMs / 1000L).toString(),
).joinToString("|")
