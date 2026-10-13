package com.nuvio.tv.ui.screens.player

/** Preview visibility follows the real seek and existing modal routing, not filenames or media hints. */
internal fun canShowTimelinePreview(
    ui: PlayerUiState,
    timeline: PlaybackTimelineState,
    foreground: Boolean,
    seekFocused: Boolean,
    seekableVideo: Boolean,
): Boolean {
    val pending = ui.pendingPreviewSeekPosition ?: return false
    return foreground && seekableVideo && !timeline.isLive && timeline.duration > 0 &&
        pending in 0..timeline.duration && !ui.isBuffering && !ui.showLoadingOverlay &&
        !ui.playbackEnded && ui.error == null && ui.pendingExitReason == null &&
        !ui.showPauseOverlay && !ui.showAudioOverlay && !ui.showSubtitleOverlay &&
        !ui.showSubtitleStylePanel && !ui.showSubtitleTimingDialog && !ui.showSubtitleDelayOverlay &&
        !ui.showSpeedDialog && !ui.showMoreDialog && !ui.showSceneBookmarks &&
        !ui.showEpisodesPanel && !ui.showSourcesPanel && !ui.showStreamInfoOverlay &&
        !ui.showPlayerEngineSwitchInfo && ui.postPlayMode == null &&
        ((ui.showControls && seekFocused) || (!ui.showControls && ui.showSeekOverlay))
}

internal fun normalizedTimelinePreviewPosition(positionMs: Long, durationMs: Long): Long? =
    if (durationMs <= 0 || positionMs !in 0..durationMs) null else positionMs.coerceAtMost(durationMs - 1)
