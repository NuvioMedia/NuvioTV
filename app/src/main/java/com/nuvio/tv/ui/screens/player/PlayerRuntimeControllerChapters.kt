package com.nuvio.tv.ui.screens.player

import androidx.media3.exoplayer.SeekParameters
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** mpv lists the chapters itself; they come with the file, so once read for a stream they are kept. */
internal fun PlayerRuntimeController.refreshMpvChapters(view: NuvioMpvSurfaceView) {
    if (mpvChaptersStreamUrl == currentStreamUrl && _uiState.value.chapters.isNotEmpty()) return
    val chapters = PlayerChapters.normalize(view.readChapters())
    if (chapters.isEmpty()) return
    mpvChaptersStreamUrl = currentStreamUrl
    _uiState.update { it.copy(chapters = chapters) }
    onChaptersLoaded(chapters)
}

/**
 * Exact on both engines: a keyframe seek can land before the chapter start, and "next" would then
 * keep returning to the same chapter. On mpv, setting the position seeks to a keyframe when the
 * file was loaded with `hr-seek=no` (the Hi10P software fallback), so chapters ask for an exact seek.
 */
internal fun PlayerRuntimeController.seekToChapter(startMs: Long) {
    if (_playbackTimeline.value.isLive) return
    pendingPreviewSeekPosition = null
    seekPlaybackTo(startMs, SeekParameters.EXACT, exact = true)
    updatePlaybackTimeline(currentPosition = startMs)
    scheduleProgressSyncAfterSeek()
    if (_uiState.value.showControls) {
        showControlsTemporarily()
    } else {
        showSeekOverlayTemporarily()
    }
}

internal fun PlayerRuntimeController.showChaptersPanel() {
    if (_uiState.value.chapters.isEmpty()) return
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showChaptersPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSpeedDialog = false,
            showMoreDialog = false
        )
    }
}

internal fun PlayerRuntimeController.dismissChaptersPanel() {
    _uiState.update { it.copy(showChaptersPanel = false) }
    scheduleHideControls()
}

/**
 * ExoPlayer does not read Matroska chapters, so they are read from the file once its first frame
 * is shown. Chapters are loaded when the chapters UI is enabled OR skip intro is enabled,
 * and only for Matroska streams (the loader returns empty for non-MKV).
 */
internal fun PlayerRuntimeController.loadExoChapters() {
    exoChapterLoadJob?.cancel()
    if (isUsingMpvEngine() || _playbackTimeline.value.isLive) return
    val url = currentStreamUrl
    val headers = currentHeaders
    exoChapterLoadJob = scope.launch {
        val settings = playerSettingsDataStore.playerSettings.first()
        if (!settings.exoChaptersEnabled && !skipIntroEnabled) return@launch
        // Only probe for chapters if the container is Matroska/WebM.
        // This avoids the extra HTTP request for MP4, HLS, and other formats.
        val containerMime = _exoPlayer?.videoFormat?.containerMimeType
        if (containerMime != null
            && containerMime != "video/x-matroska"
            && containerMime != "video/webm"
        ) return@launch
        val chapters = MatroskaChapterLoader.load(url, headers)
        if (chapters.isEmpty() || isUsingMpvEngine() || currentStreamUrl != url) return@launch
        if (settings.exoChaptersEnabled) {
            _uiState.update { it.copy(chapters = chapters) }
        }
        onChaptersLoaded(chapters)
    }
}

/**
 * Store raw chapters for skip detection (independent of UI list) and compute intervals
 * if duration is already known. The raw chapters are kept so they survive the UI list
 * being cleared (e.g. chapters panel off on Exo) and can be recomputed when skip intro
 * is toggled on or the duration becomes available.
 */
private fun PlayerRuntimeController.onChaptersLoaded(chapters: List<PlayerChapter>) {
    skipDetectionChapters = chapters
    skipChaptersComputedForDuration = 0L
    if (skipIntroEnabled) {
        tryComputeChapterSkipIntervals()
    }
}

/**
 * Compute chapter skip intervals if the duration is known and we haven't already done so
 * for this stream at this duration. Sets a flag to avoid re-running regexes on every tick.
 */
private fun PlayerRuntimeController.tryComputeChapterSkipIntervals(): Boolean {
    val chapters = skipDetectionChapters.takeIf { it.size >= 2 } ?: return false
    val durationMs = _playbackTimeline.value.duration.takeIf { it > 0L } ?: return false
    if (skipChaptersComputedForDuration == durationMs) return false
    skipChaptersComputedForDuration = durationMs
    chapterSkipIntervals = ChapterSkipIntervals.fromChapters(chapters, durationMs)
    recomputeMergedSkipIntervals()
    return true
}

/**
 * Called from the playback tick. Retries chapter interval computation once the duration
 * becomes known, then stops. Guarded by skipIntroEnabled.
 */
internal fun PlayerRuntimeController.recomputeChapterSkipIntervalsIfNeeded() {
    if (!skipIntroEnabled) return
    if (skipChaptersComputedForDuration > 0L) return // already computed for this stream
    tryComputeChapterSkipIntervals()
}

internal fun PlayerRuntimeController.clearChapterSkipState() {
    skipDetectionChapters = emptyList()
    chapterSkipIntervals = emptyList()
    skipChaptersComputedForDuration = 0L
}
