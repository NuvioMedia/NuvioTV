package com.nuvio.tv.ui.screens.player

import android.util.Log
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * The titles of the disc a Blu-ray clip belongs to, from the clip probe. Offered when there is
 * more than one and the clip plays as one of them, the feature.
 */
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

internal fun PlayerRuntimeController.resetDiscTitles() {
    discTitleLanguagesJob?.cancel()
    discTitleLanguagesJob = null
    discTitlePlaylists = emptyMap()
    discFeatureTitle = null
    discFeaturePositionMs = 0L
    _uiState.update { it.copy(discTitles = emptyList(), currentDiscTitle = null, showDiscTitlesDialog = false) }
}

/** Another title of the disc than its feature, an extra: not the item's progress. */
internal fun PlayerRuntimeController.isPlayingDiscExtra(): Boolean =
    discFeatureTitle != null && _uiState.value.currentDiscTitle != discFeatureTitle

/** Each title's languages, from its first clip's CLPI, once the titles are first shown. */
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

/**
 * On an engine switch, an extra of the disc gives way to its feature. Returns where the
 * feature was left, or null when the feature is playing.
 */
internal fun PlayerRuntimeController.returnToDiscFeature(): Long? {
    if (!isPlayingDiscExtra()) return null
    val feature = discFeatureTitle?.let { discTitlePlaylists[it] } ?: return null
    currentStreamBluray = feature.takeUnless { it.segmentUrls == listOf(currentStreamUrl) }
    _uiState.update { it.copy(currentDiscTitle = feature.name, showDiscTitlesDialog = false) }
    return discFeaturePositionMs
}

/** Plays another title of the disc from its start, or the feature from where it was left. */
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
        // The title switches only once the player is released: the release must not save the
        // extra's position as the feature's, or the reverse.
        releasePlayer(flushPlaybackState = false)
        // The clip itself needs no playlist; the probe already ran for it.
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
        initializePlayer(currentStreamUrl, currentHeaders)
    }
}
