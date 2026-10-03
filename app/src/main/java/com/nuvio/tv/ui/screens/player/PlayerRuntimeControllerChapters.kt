package com.nuvio.tv.ui.screens.player

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
