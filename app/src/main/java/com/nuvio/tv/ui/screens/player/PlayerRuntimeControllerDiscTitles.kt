package com.nuvio.tv.ui.screens.player

import android.util.Log
import android.widget.Toast
import com.nuvio.tv.R
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.showDiscTitles(disc: BlurayPlaylistResolver.DiscProbe) {
    if (disc.titles.size < 2) return
    val feature = disc.currentTitle?.takeIf { name -> disc.titles.any { it.name == name } } ?: return
    discTitlePlaylists = disc.titles.associateBy { it.name }
    discFeatureTitle = feature
    discFeaturePositionMs = 0L
    _uiState.update { state ->
        state.copy(
            discTitles = disc.titles.map { title ->
                DiscTitle(
                    name = title.name,
                    durationMs = title.durationsMs.sum(),
                    clipCount = title.segmentUrls.size,
                    isFeature = title.name == feature
                )
            },
            currentDiscTitle = feature
        )
    }
}

internal fun PlayerRuntimeController.isBlurayPlaybackAllowed(): Boolean {
    val url = currentStreamUrl
    return !com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry.isLiveTv(url) &&
        !com.nuvio.tv.reshaped.livetv.LiveTvPlaybackRegistry.isCatchup(url) &&
        !LivePlaybackUiPolicy.isLiveContentType(contentType)
}

internal fun PlayerRuntimeController.resetDiscTitles() {
    discTitleLanguagesJob?.cancel()
    discTitleLanguagesJob = null
    discTitlePlaylists = emptyMap()
    discFeatureTitle = null
    discFeaturePositionMs = 0L
    _uiState.update { it.copy(discTitles = emptyList(), currentDiscTitle = null, showDiscTitlesDialog = false) }
}

internal fun PlayerRuntimeController.isPlayingDiscExtra(): Boolean =
    discFeatureTitle != null && _uiState.value.currentDiscTitle != discFeatureTitle

internal fun PlayerRuntimeController.loadDiscTitleLanguages() {
    if (discTitleLanguagesJob != null) return
    val headers = currentHeaders
    discTitleLanguagesJob = scope.launch {
        val byClip = HashMap<String, Pair<List<String>, List<String>>>()
        for (title in _uiState.value.discTitles) {
            val playlist = discTitlePlaylists[title.name] ?: continue
            val clip = playlist.segmentUrls.firstOrNull() ?: continue
            val (audio, subtitles) = byClip[clip]
                ?: PlayerMediaSourceFactory.probeBlurayTitleLanguages(playlist, headers).also { byClip[clip] = it }
            _uiState.update { state ->
                state.copy(
                    discTitles = state.discTitles.map {
                        if (it.name == title.name) it.copy(audioLanguages = audio, subtitleLanguages = subtitles) else it
                    }
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.returnToDiscFeature(): Long? {
    if (!isPlayingDiscExtra()) return null
    val feature = discFeatureTitle?.let { discTitlePlaylists[it] } ?: return null
    currentStreamBluray = feature.takeUnless { it.segmentUrls == listOf(currentStreamUrl) }
    _uiState.update { it.copy(currentDiscTitle = feature.name, showDiscTitlesDialog = false) }
    return discFeaturePositionMs
}

internal fun PlayerRuntimeController.returnToFeatureAfterExtraFailure(error: String): Boolean {
    if (!isPlayingDiscExtra()) return false
    val feature = discFeatureTitle?.takeIf { it in discTitlePlaylists } ?: return false
    Log.w(PlayerRuntimeController.TAG, "Disc title ${_uiState.value.currentDiscTitle} failed, returning to $feature: $error")
    Toast.makeText(context, context.getString(R.string.player_disc_title_failed), Toast.LENGTH_SHORT).show()
    selectDiscTitle(feature)
    return true
}

internal fun PlayerRuntimeController.selectDiscTitle(name: String) {
    val playlist = discTitlePlaylists[name] ?: return
    _uiState.update { it.copy(showDiscTitlesDialog = false) }
    if (name == _uiState.value.currentDiscTitle) {
        scheduleHideControls()
        return
    }
    if (!isPlayingDiscExtra()) {
        discFeaturePositionMs = currentPlaybackPositionMs() ?: 0L
        saveWatchProgress()
        emitPauseScrobbleForCurrentProgress()
    }
    val startMs = if (name == discFeatureTitle) discFeaturePositionMs else 0L
    Log.i(PlayerRuntimeController.TAG, "Playing disc title $name: ${playlist.segmentUrls.size} clips from ${startMs}ms")
    cancelFirstFrameWatchdog()
    cancelStallWatchdog()
    pendingResumeProgress = null
    scope.launch {
        releasePlayer(flushPlaybackState = false)
        currentStreamBluray = playlist.takeUnless { it.segmentUrls == listOf(currentStreamUrl) }
        clipPlaylistProbedUrl = currentStreamUrl
        _uiState.update {
            it.copy(
                currentDiscTitle = name,
                pendingSeekPosition = startMs.takeIf { position -> position > 0L },
                error = null,
                showLoadingOverlay = it.loadingOverlayEnabled
            )
        }
        initializePlayer(currentStreamUrl, currentHeaders, overrideInternalPlayerEngine = runtimeInternalPlayerEngineOverride)
    }
}
