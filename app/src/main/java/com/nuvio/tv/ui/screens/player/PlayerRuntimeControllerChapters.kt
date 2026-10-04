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
}

/**
 * Exact: a keyframe seek can land before the chapter start, and "next" would then keep returning
 * to the same chapter.
 */
internal fun PlayerRuntimeController.seekToChapter(startMs: Long) {
    if (_playbackTimeline.value.isLive) return
    pendingPreviewSeekPosition = null
    seekPlaybackTo(startMs, SeekParameters.EXACT)
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
 * is shown, when the extra requests no longer compete with starting playback.
 */
internal fun PlayerRuntimeController.loadExoChapters() {
    exoChapterLoadJob?.cancel()
    if (isUsingMpvEngine() || _playbackTimeline.value.isLive) return
    val url = currentStreamUrl
    val headers = currentHeaders
    exoChapterLoadJob = scope.launch {
        if (!playerSettingsDataStore.playerSettings.first().exoChaptersEnabled) return@launch
        val chapters = MatroskaChapterLoader.load(url, headers)
        if (chapters.isEmpty() || isUsingMpvEngine() || currentStreamUrl != url) return@launch
        _uiState.update { it.copy(chapters = chapters) }
    }
}
