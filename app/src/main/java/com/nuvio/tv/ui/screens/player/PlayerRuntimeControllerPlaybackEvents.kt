package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.Player
import com.nuvio.tv.data.local.SubtitleStyleSettings
import com.nuvio.tv.data.repository.TraktScrobbleItem
import com.nuvio.tv.data.repository.extractYear
import com.nuvio.tv.data.repository.parseContentIds
import com.nuvio.tv.data.repository.toTraktIds
import com.nuvio.tv.domain.model.WatchProgress
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.startProgressUpdates() {
    progressJob?.cancel()
    progressJob = scope.launch {
        while (isActive) {
            _exoPlayer?.let { player ->
                val pos = player.currentPosition.coerceAtLeast(0L)
                val playerDuration = player.duration
                if (playerDuration > lastKnownDuration) {
                    lastKnownDuration = playerDuration
                }
                val displayPosition = pendingPreviewSeekPosition ?: pos
                _uiState.update {
                    it.copy(
                        currentPosition = displayPosition,
                        duration = playerDuration.coerceAtLeast(0L)
                    )
                }
                updateActiveSkipInterval(pos)
                evaluateNextEpisodeCardVisibility(
                    positionMs = pos,
                    durationMs = playerDuration.coerceAtLeast(0L)
                )

                
                if (player.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastBufferLogTimeMs >= 10_000) {
                        lastBufferLogTimeMs = now
                        val bufAhead = (player.bufferedPosition - player.currentPosition) / 1000
                        val loading = player.isLoading
                        val runtime = Runtime.getRuntime()
                        val usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)
                        val maxMb = runtime.maxMemory() / (1024 * 1024)
                        Log.d(PlayerRuntimeController.TAG, "BUFFER: ahead=${bufAhead}s, loading=$loading, heap=${usedMb}/${maxMb}MB, pos=${pos / 1000}s")
                    }
                }
            }
            delay(500)
        }
    }
}

internal fun PlayerRuntimeController.stopProgressUpdates() {
    progressJob?.cancel()
    progressJob = null
}

internal fun PlayerRuntimeController.startWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = scope.launch {
        while (isActive) {
            delay(10000)
            saveWatchProgressIfNeeded()
        }
    }
}

internal fun PlayerRuntimeController.stopWatchProgressSaving() {
    watchProgressSaveJob?.cancel()
    watchProgressSaveJob = null
}

internal fun PlayerRuntimeController.saveWatchProgressIfNeeded() {
    val currentPosition = _exoPlayer?.currentPosition ?: return
    val duration = getEffectiveDuration(currentPosition)
    
    
    if (kotlin.math.abs(currentPosition - lastSavedPosition) >= saveThresholdMs) {
        lastSavedPosition = currentPosition
        saveWatchProgressInternal(currentPosition, duration, syncRemote = false)
    }
}

internal fun PlayerRuntimeController.saveWatchProgress() {
    val currentPosition = _exoPlayer?.currentPosition ?: return
    val duration = getEffectiveDuration(currentPosition)
    saveWatchProgressInternal(currentPosition, duration)
}

internal fun PlayerRuntimeController.getEffectiveDuration(position: Long): Long {
    val playerDuration = _exoPlayer?.duration ?: 0L
    val effectiveDuration = maxOf(playerDuration, lastKnownDuration)
    if (effectiveDuration <= 0L) return 0L

    val isEnded = _exoPlayer?.playbackState == Player.STATE_ENDED
    if (!isEnded && effectiveDuration < position) return 0L

    return effectiveDuration
}

internal fun PlayerRuntimeController.saveWatchProgressInternal(position: Long, duration: Long, syncRemote: Boolean = true) {
    
    if (contentId.isNullOrEmpty() || contentType.isNullOrEmpty()) return
    
    if (position < 1000) return

    val fallbackPercent = if (duration <= 0L) 5f else null

    val progress = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = contentName ?: title,
        poster = poster,
        backdrop = backdrop,
        logo = logo,
        videoId = currentVideoId ?: contentId,
        season = currentSeason,
        episode = currentEpisode,
        episodeTitle = currentEpisodeTitle,
        position = position,
        duration = duration,
        lastWatched = System.currentTimeMillis(),
        progressPercent = fallbackPercent
    )

    scope.launch {
        watchProgressRepository.saveProgress(progress, syncRemote = syncRemote)
    }
}

internal fun PlayerRuntimeController.currentPlaybackProgressPercent(): Float {
    val player = _exoPlayer ?: return 0f
    val duration = player.duration.takeIf { it > 0 } ?: lastKnownDuration
    if (duration <= 0L) return 0f
    return ((player.currentPosition.toFloat() / duration.toFloat()) * 100f).coerceIn(0f, 100f)
}

internal fun PlayerRuntimeController.refreshScrobbleItem() {
    currentScrobbleItem = buildScrobbleItem()
    hasSentScrobbleStartForCurrentItem = false
    hasSentCompletionScrobbleForCurrentItem = false
}

internal fun PlayerRuntimeController.buildScrobbleItem(): TraktScrobbleItem? {
    val rawContentId = contentId ?: return null
    val parsedIds = parseContentIds(rawContentId)
    val ids = toTraktIds(parsedIds)
    val parsedYear = extractYear(year)
    val normalizedType = contentType?.lowercase()

    val isEpisode = normalizedType in listOf("series", "tv") &&
        currentSeason != null && currentEpisode != null

    return if (isEpisode) {
        TraktScrobbleItem.Episode(
            showTitle = contentName ?: title,
            showYear = parsedYear,
            showIds = ids,
            season = currentSeason ?: return null,
            number = currentEpisode ?: return null,
            episodeTitle = currentEpisodeTitle
        )
    } else {
        TraktScrobbleItem.Movie(
            title = contentName ?: title,
            year = parsedYear,
            ids = ids
        )
    }
}

internal fun PlayerRuntimeController.emitScrobbleStart() {
    val item = currentScrobbleItem ?: buildScrobbleItem().also { currentScrobbleItem = it } ?: return
    if (hasSentScrobbleStartForCurrentItem) return

    scope.launch {
        traktScrobbleService.scrobbleStart(
            item = item,
            progressPercent = currentPlaybackProgressPercent()
        )
        hasSentScrobbleStartForCurrentItem = true
    }
}

internal fun PlayerRuntimeController.emitScrobbleStop(progressPercent: Float? = null) {
    val item = currentScrobbleItem ?: return
    if (!hasSentScrobbleStartForCurrentItem && (progressPercent ?: 0f) < 80f) return

    val percent = progressPercent ?: currentPlaybackProgressPercent()
    scope.launch {
        traktScrobbleService.scrobbleStop(
            item = item,
            progressPercent = percent
        )
    }
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitPauseScrobbleStop(progressPercent: Float) {
    if (progressPercent < 1f || progressPercent >= 80f) return
    val item = currentScrobbleItem ?: return
    if (!hasSentScrobbleStartForCurrentItem) return

    scope.launch {
        traktScrobbleService.scrobblePause(
            item = item,
            progressPercent = progressPercent
        )
    }
    hasSentScrobbleStartForCurrentItem = false
}

internal fun PlayerRuntimeController.emitCompletionScrobbleStop(progressPercent: Float) {
    if (progressPercent < 80f || hasSentCompletionScrobbleForCurrentItem) return
    hasSentCompletionScrobbleForCurrentItem = true
    emitScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.emitStopScrobbleForCurrentProgress() {
    val progressPercent = currentPlaybackProgressPercent()
    emitPauseScrobbleStop(progressPercent = progressPercent)
    emitCompletionScrobbleStop(progressPercent = progressPercent)
}

internal fun PlayerRuntimeController.flushPlaybackSnapshotForSwitchOrExit() {
    emitStopScrobbleForCurrentProgress()
    saveWatchProgress()
}

internal fun PlayerRuntimeController.scheduleProgressSyncAfterSeek() {
    seekProgressSyncJob?.cancel()
    seekProgressSyncJob = scope.launch {
        delay(seekProgressSyncDebounceMs)
        saveWatchProgress()

        val progressPercent = currentPlaybackProgressPercent()
        emitPauseScrobbleStop(progressPercent = progressPercent)

        if (_exoPlayer?.isPlaying == true && progressPercent >= 1f && progressPercent < 80f) {
            emitScrobbleStart()
        }
    }
}

fun PlayerRuntimeController.scheduleHideControls() {
    hideControlsJob?.cancel()
    hideControlsJob = scope.launch {
        delay(3000)
        if (_uiState.value.isPlaying && !_uiState.value.showAudioDialog &&
            !_uiState.value.showSubtitleDialog && !_uiState.value.showSubtitleStylePanel &&
            !_uiState.value.showSpeedDialog && !_uiState.value.showMoreDialog &&
            !_uiState.value.showSubtitleDelayOverlay &&
            !_uiState.value.showEpisodesPanel && !_uiState.value.showSourcesPanel) {
            _uiState.update { it.copy(showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.showSubtitleDelayOverlay() {
    hideControlsJob?.cancel()
    _uiState.update {
        it.copy(
            showControls = false,
            showSubtitleDelayOverlay = true,
            showAudioDialog = false,
            showSubtitleDialog = false,
            showSubtitleStylePanel = false,
            showSpeedDialog = false
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.hideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = null
    _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
}

internal fun PlayerRuntimeController.adjustSubtitleDelay(deltaMs: Int) {
    val currentDelayMs = _uiState.value.subtitleDelayMs
    val newDelayMs = (currentDelayMs + deltaMs).coerceIn(
        minimumValue = SUBTITLE_DELAY_MIN_MS,
        maximumValue = SUBTITLE_DELAY_MAX_MS
    )

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    _uiState.update {
        it.copy(
            subtitleDelayMs = newDelayMs,
            showControls = false,
            showSubtitleDelayOverlay = true
        )
    }
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.scheduleHideSubtitleDelayOverlay() {
    hideSubtitleDelayOverlayJob?.cancel()
    hideSubtitleDelayOverlayJob = scope.launch {
        delay(SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS)
        _uiState.update { it.copy(showSubtitleDelayOverlay = false) }
    }
}

internal fun PlayerRuntimeController.schedulePauseOverlay() {
    pauseOverlayJob?.cancel()

    if (!_uiState.value.pauseOverlayEnabled || !hasRenderedFirstFrame || !userPausedManually) {
        _uiState.update { it.copy(showPauseOverlay = false) }
        return
    }

    _uiState.update { it.copy(showPauseOverlay = false) }
    pauseOverlayJob = scope.launch {
        delay(pauseOverlayDelayMs)
        if (!_uiState.value.isPlaying && _uiState.value.pauseOverlayEnabled && _uiState.value.error == null) {
            _uiState.update { it.copy(showPauseOverlay = true, showControls = false) }
        }
    }
}

internal fun PlayerRuntimeController.cancelPauseOverlay() {
    pauseOverlayJob?.cancel()
    pauseOverlayJob = null
    _uiState.update { it.copy(showPauseOverlay = false) }
}

fun PlayerRuntimeController.onUserInteraction() {
    
    if (_uiState.value.showPauseOverlay || pauseOverlayJob != null) {
        cancelPauseOverlay()
    }
}

fun PlayerRuntimeController.hideControls() {
    hideControlsJob?.cancel()
    _uiState.update { it.copy(showControls = false, showSeekOverlay = false) }
}

fun PlayerRuntimeController.onEvent(event: PlayerEvent) {
    onUserInteraction()
    when (event) {
        PlayerEvent.OnPlayPause -> {
            _exoPlayer?.let { player ->
                if (player.isPlaying) {
                    userPausedManually = true
                    player.pause()
                    schedulePauseOverlay()
                } else {
                    userPausedManually = false
                    cancelPauseOverlay()
                    player.play()
                }
            }
            showControlsTemporarily()
        }
        PlayerEvent.OnSeekForward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = 10_000L))
        }
        PlayerEvent.OnSeekBackward -> {
            onEvent(PlayerEvent.OnSeekBy(deltaMs = -10_000L))
        }
        is PlayerEvent.OnSeekBy -> {
            pendingPreviewSeekPosition = null
            _exoPlayer?.let { player ->
                val maxDuration = player.duration.takeIf { it >= 0 } ?: Long.MAX_VALUE
                val target = (player.currentPosition + event.deltaMs)
                    .coerceAtLeast(0L)
                    .coerceAtMost(maxDuration)
                player.seekTo(target)
                _uiState.update { it.copy(currentPosition = target) }
                scheduleProgressSyncAfterSeek()
            }
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnPreviewSeekBy -> {
            _exoPlayer?.let { player ->
                val maxDuration = player.duration.takeIf { it >= 0 } ?: Long.MAX_VALUE
                val basePosition = pendingPreviewSeekPosition ?: player.currentPosition.coerceAtLeast(0L)
                val target = (basePosition + event.deltaMs)
                    .coerceAtLeast(0L)
                    .coerceAtMost(maxDuration)
                pendingPreviewSeekPosition = target
                _uiState.update { it.copy(currentPosition = target) }
            }
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        PlayerEvent.OnCommitPreviewSeek -> {
            val target = pendingPreviewSeekPosition
            if (target != null) {
                _exoPlayer?.seekTo(target)
                _uiState.update { it.copy(currentPosition = target) }
                pendingPreviewSeekPosition = null
                scheduleProgressSyncAfterSeek()
                if (_uiState.value.showControls) {
                    showControlsTemporarily()
                } else {
                    showSeekOverlayTemporarily()
                }
            }
        }
        is PlayerEvent.OnSeekTo -> {
            pendingPreviewSeekPosition = null
            _exoPlayer?.seekTo(event.position)
            _uiState.update { it.copy(currentPosition = event.position) }
            scheduleProgressSyncAfterSeek()
            if (_uiState.value.showControls) {
                showControlsTemporarily()
            } else {
                showSeekOverlayTemporarily()
            }
        }
        is PlayerEvent.OnSelectAudioTrack -> {
            selectAudioTrack(event.index)
            _uiState.update { it.copy(showAudioDialog = false, showSubtitleDelayOverlay = false) }
        }
        is PlayerEvent.OnSelectSubtitleTrack -> {
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            selectSubtitleTrack(event.index)
            _uiState.update { 
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleStylePanel = false,
                    showSubtitleDelayOverlay = false,
                    selectedAddonSubtitle = null 
                ) 
            }
        }
        PlayerEvent.OnDisableSubtitles -> {
            autoSubtitleSelected = true
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            disableSubtitles()
            _uiState.update { 
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleStylePanel = false,
                    showSubtitleDelayOverlay = false,
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                ) 
            }
        }
        is PlayerEvent.OnSelectAddonSubtitle -> {
            autoSubtitleSelected = true
            selectAddonSubtitle(event.subtitle)
            _uiState.update {
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleStylePanel = false,
                    showSubtitleDelayOverlay = false
                )
            }
        }
        is PlayerEvent.OnSetPlaybackSpeed -> {
            _exoPlayer?.setPlaybackSpeed(event.speed)
            _uiState.update { 
                it.copy(
                    playbackSpeed = event.speed,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false
                ) 
            }
        }
        PlayerEvent.OnToggleControls -> {
            if (_uiState.value.showSubtitleDelayOverlay) {
                hideSubtitleDelayOverlay()
            }
            _uiState.update { it.copy(showControls = !it.showControls) }
            if (_uiState.value.showControls) {
                scheduleHideControls()
            }
        }
        PlayerEvent.OnShowAudioDialog -> {
            _uiState.update {
                it.copy(
                    showAudioDialog = true,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowSubtitleDialog -> {
            _uiState.update {
                it.copy(
                    showSubtitleDialog = true,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnOpenSubtitleStylePanel -> {
            _uiState.update {
                it.copy(
                    showSubtitleDialog = false,
                    showSubtitleStylePanel = true,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissSubtitleStylePanel -> {
            _uiState.update { it.copy(showSubtitleStylePanel = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowSubtitleDelayOverlay -> {
            showSubtitleDelayOverlay()
        }
        PlayerEvent.OnHideSubtitleDelayOverlay -> {
            hideSubtitleDelayOverlay()
        }
        is PlayerEvent.OnAdjustSubtitleDelay -> {
            adjustSubtitleDelay(event.deltaMs)
        }
        PlayerEvent.OnShowSpeedDialog -> {
            _uiState.update {
                it.copy(
                    showSpeedDialog = true,
                    showSubtitleStylePanel = false,
                    showMoreDialog = false,
                    showSubtitleDelayOverlay = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnShowMoreDialog -> {
            _uiState.update {
                it.copy(
                    showMoreDialog = true,
                    showAudioDialog = false,
                    showSubtitleDialog = false,
                    showSubtitleStylePanel = false,
                    showSubtitleDelayOverlay = false,
                    showSpeedDialog = false,
                    showControls = true
                )
            }
        }
        PlayerEvent.OnDismissMoreDialog -> {
            _uiState.update { it.copy(showMoreDialog = false) }
            scheduleHideControls()
        }
        PlayerEvent.OnShowEpisodesPanel -> {
            showEpisodesPanel()
        }
        PlayerEvent.OnDismissEpisodesPanel -> {
            dismissEpisodesPanel()
        }
        PlayerEvent.OnBackFromEpisodeStreams -> {
            _uiState.update {
                it.copy(
                    showEpisodeStreams = false,
                    isLoadingEpisodeStreams = false
                )
            }
        }
        is PlayerEvent.OnEpisodeSeasonSelected -> {
            selectEpisodesSeason(event.season)
        }
        is PlayerEvent.OnEpisodeSelected -> {
            loadStreamsForEpisode(event.video)
        }
        PlayerEvent.OnReloadEpisodeStreams -> {
            reloadEpisodeStreams()
        }
        is PlayerEvent.OnEpisodeAddonFilterSelected -> {
            filterEpisodeStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnEpisodeStreamSelected -> {
            switchToEpisodeStream(event.stream)
        }
        PlayerEvent.OnShowSourcesPanel -> {
            showSourcesPanel()
        }
        PlayerEvent.OnDismissSourcesPanel -> {
            dismissSourcesPanel()
        }
        PlayerEvent.OnReloadSourceStreams -> {
            loadSourceStreams(forceRefresh = true)
        }
        is PlayerEvent.OnSourceAddonFilterSelected -> {
            filterSourceStreamsByAddon(event.addonName)
        }
        is PlayerEvent.OnSourceStreamSelected -> {
            switchToSourceStream(event.stream)
        }
        PlayerEvent.OnDismissDialog -> {
            _uiState.update { 
                it.copy(
                    showAudioDialog = false, 
                    showSubtitleDialog = false, 
                    showSubtitleStylePanel = false,
                    showSpeedDialog = false,
                    showSubtitleDelayOverlay = false,
                    showMoreDialog = false
                ) 
            }
            scheduleHideControls()
        }
        PlayerEvent.OnRetry -> {
            hasRenderedFirstFrame = false
            hasRetriedCurrentStreamAfter416 = false
            resetNextEpisodeCardState(clearEpisode = false)
            _uiState.update { state ->
                state.copy(
                    error = null,
                    showLoadingOverlay = state.loadingOverlayEnabled,
                    showSubtitleDelayOverlay = false
                )
            }
            releasePlayer()
            initializePlayer(currentStreamUrl, currentHeaders)
        }
        PlayerEvent.OnParentalGuideHide -> {
            _uiState.update { it.copy(showParentalGuide = false) }
        }
        is PlayerEvent.OnShowDisplayModeInfo -> {
            _uiState.update {
                it.copy(
                    displayModeInfo = event.info,
                    showDisplayModeInfo = true
                )
            }
        }
        PlayerEvent.OnHideDisplayModeInfo -> {
            _uiState.update { it.copy(showDisplayModeInfo = false) }
        }
        PlayerEvent.OnDismissPauseOverlay -> {
            cancelPauseOverlay()
        }
        PlayerEvent.OnSkipIntro -> {
            _uiState.value.activeSkipInterval?.let { interval ->
                _exoPlayer?.seekTo((interval.endTime * 1000).toLong())
                scheduleProgressSyncAfterSeek()
                _uiState.update { it.copy(activeSkipInterval = null, skipIntervalDismissed = true) }
            }
        }
        PlayerEvent.OnDismissSkipIntro -> {
            _uiState.update { it.copy(skipIntervalDismissed = true) }
        }
        PlayerEvent.OnPlayNextEpisode -> {
            playNextEpisode()
        }
        PlayerEvent.OnDismissNextEpisodeCard -> {
            nextEpisodeAutoPlayJob?.cancel()
            nextEpisodeAutoPlayJob = null
            _uiState.update {
                it.copy(
                    showNextEpisodeCard = false,
                    nextEpisodeCardDismissed = true,
                    nextEpisodeAutoPlaySearching = false,
                    nextEpisodeAutoPlaySourceName = null,
                    nextEpisodeAutoPlayCountdownSec = null
                )
            }
        }
        is PlayerEvent.OnSetSubtitleSize -> {
            scope.launch { playerSettingsDataStore.setSubtitleSize(event.size) }
        }
        is PlayerEvent.OnSetSubtitleTextColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleTextColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleBold -> {
            scope.launch { playerSettingsDataStore.setSubtitleBold(event.bold) }
        }
        is PlayerEvent.OnSetSubtitleOutlineEnabled -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineEnabled(event.enabled) }
        }
        is PlayerEvent.OnSetSubtitleOutlineColor -> {
            scope.launch { playerSettingsDataStore.setSubtitleOutlineColor(event.color) }
        }
        is PlayerEvent.OnSetSubtitleVerticalOffset -> {
            scope.launch { playerSettingsDataStore.setSubtitleVerticalOffset(event.offset) }
        }
        PlayerEvent.OnResetSubtitleDefaults -> {
            scope.launch {
                val defaults = SubtitleStyleSettings()
                playerSettingsDataStore.setSubtitleSize(defaults.size)
                playerSettingsDataStore.setSubtitleTextColor(defaults.textColor)
                playerSettingsDataStore.setSubtitleBold(defaults.bold)
                playerSettingsDataStore.setSubtitleOutlineEnabled(defaults.outlineEnabled)
                playerSettingsDataStore.setSubtitleOutlineColor(defaults.outlineColor)
                playerSettingsDataStore.setSubtitleOutlineWidth(defaults.outlineWidth)
                playerSettingsDataStore.setSubtitleVerticalOffset(defaults.verticalOffset)
                playerSettingsDataStore.setSubtitleBackgroundColor(defaults.backgroundColor)
            }
        }
        PlayerEvent.OnToggleAspectRatio -> {
            val currentMode = _uiState.value.resizeMode
            val newMode = PlayerDisplayModeUtils.nextResizeMode(currentMode)
            val modeText = PlayerDisplayModeUtils.resizeModeLabel(newMode)
            Log.d("PlayerViewModel", "Aspect ratio toggled: $currentMode -> $newMode")
            _uiState.update { 
                it.copy(
                    resizeMode = newMode,
                    showAspectRatioIndicator = true,
                    aspectRatioIndicatorText = modeText
                ) 
            }
            // Auto-hide indicator after 1.5 seconds
            hideAspectRatioIndicatorJob?.cancel()
            hideAspectRatioIndicatorJob = scope.launch {
                delay(1500)
                _uiState.update { it.copy(showAspectRatioIndicator = false) }
            }
        }
    }
}
