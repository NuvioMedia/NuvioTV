package com.nuvio.tv.ui.screens.player.seekpreview

import com.nuvio.tv.ui.screens.player.PlayerEvent
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.currentPlaybackDurationMs
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSource
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalPreviewSources
import com.nuvio.tv.ui.screens.player.seekpreview.local.LocalSeekPreviewSettings
import com.nuvio.tv.ui.screens.player.seekpreview.local.localSeekPreviewCacheKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.transformLatest

/**
 * Seek-preview state for one player session: loaded track and cue behind the frame on screen.
 * Generated purely on-device from video keyframes.
 */
class SeekPreviewState internal constructor(
    scope: CoroutineScope,
    private val controller: PlayerRuntimeController,
) {
    private val _previewCue = MutableStateFlow<SeekPreviewCue?>(null)

    /**
     * Cue window (playback timebase) behind the preview frame currently on screen, or `null`
     * while no preview has resolved. Drives grid-locked scrubbing and the scrubber's cue ticks.
     */
    val previewCue: StateFlow<SeekPreviewCue?> = _previewCue.asStateFlow()

    private val _offsetMs = MutableStateFlow(0)
    val offsetMs: StateFlow<Int> = _offsetMs.asStateFlow()

    /**
     * On-device thumbnails for the stream this player shows with ExoPlayer, while "Generate
     * previews on device" is on. Opened when the stream registers and its duration is known,
     * closed (and saved to the disk cache) when either changes or the player goes away.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    val previewTrack: StateFlow<SeekPreviewTrack?> =
        combine(
            // Live windows have no fixed timeline to hang thumbnails on.
            controller.playbackTimeline.map { if (it.isLive) 0L else it.duration }.distinctUntilChanged(),
            LocalSeekPreviewSettings.enabled(controller.context),
            LocalPreviewSources.sourceFor(controller)
        ) { durationMs, enabled, source ->
            Triple(durationMs, enabled, source)
        }
            .distinctUntilChanged()
            .transformLatest<Triple<Long, Boolean, LocalPreviewSource?>, SeekPreviewTrack?> { (durationMs, enabled, source) ->
                if (!enabled || source == null || durationMs <= 0L) {
                    emit(null)
                    return@transformLatest
                }
                val cacheKey = localSeekPreviewCacheKey(
                    contentId = controller.contentId,
                    season = controller.currentSeason,
                    episode = controller.currentEpisode,
                    durationMs = durationMs
                )
                val opened = LocalPreviewSources.open(source, cacheKey, durationMs)
                if (opened == null) {
                    emit(null)
                    return@transformLatest
                }
                try {
                    emit(opened)
                    awaitCancellation()
                } finally {
                    LocalPreviewSources.close(source, opened)
                }
            }
            .stateIn(scope, SharingStarted.Eagerly, null)

    /** Spacing between preview cues, or 0 when no preview has resolved. */
    val cueIntervalMs: StateFlow<Long> =
        previewCue
            .map { it?.durationMs ?: 0L }
            .distinctUntilChanged()
            .stateIn(scope, SharingStarted.Eagerly, 0L)

    /**
     * Grid-locked scrubbing: rewrites a D-pad preview step so it lands on a position an actual
     * preview frame exists for, so the thumbnail and the eventual seek can never disagree.
     * Every other event, and every step without a resolved cue, passes through unchanged.
     */
    internal fun intercept(event: PlayerEvent): PlayerEvent {
        if (event !is PlayerEvent.OnPreviewSeekBy || previewTrack.value == null) return event
        if (controller.playbackTimeline.value.isLive) return event
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val basePosition = controller.pendingPreviewSeekPosition
            ?: controller.currentPlaybackPositionMs()?.coerceAtLeast(0L)
            ?: 0L
        val target = SeekPreviewCueStepper.targetMs(
            cue = _previewCue.value,
            fromMs = basePosition,
            deltaMs = event.deltaMs,
            durationMs = maxDuration
        )
        return PlayerEvent.OnPreviewSeekBy(target - basePosition)
    }

    /**
     * Reported by the thumbnail host once it knows which cue the frame on screen came from.
     * Snapping the pending scrub position onto its start keeps the number under the thumbnail
     * honest even when the cue grid is not perfectly uniform.
     */
    fun onPreviewCueResolved(cue: SeekPreviewCue?) {
        _previewCue.value = cue
        if (controller.playbackTimeline.value.isLive) return
        val maxDuration = controller.currentPlaybackDurationMs().takeIf { it >= 0 } ?: Long.MAX_VALUE
        val aligned = SeekPreviewCueStepper.alignedTargetMs(
            cue = cue,
            pendingMs = controller.pendingPreviewSeekPosition,
            durationMs = maxDuration
        ) ?: return
        controller.pendingPreviewSeekPosition = aligned
        controller.updatePlaybackTimeline(currentPosition = aligned)
    }
}
