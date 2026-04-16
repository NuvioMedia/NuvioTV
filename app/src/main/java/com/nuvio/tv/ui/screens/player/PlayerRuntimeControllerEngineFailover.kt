package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.maybeAutoSwitchInternalPlayerOnStartupError(
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (!allowEngineFailover) return false
    if (!autoSwitchInternalPlayerOnErrorEnabled) return false
    if (startupEngineFailoverTriggered) return false
    if (!isStartupPhaseForEngineFailover()) return false

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
    }
    beginSwitchTraceSession(reason = "startup-failover", targetEngine = targetEngine)
    logSwitchTrace(
        stage = "startup-failover-trigger",
        message = "allowEngineFailover=$allowEngineFailover detailedError=${detailedError.take(220)}"
    )
    rememberCurrentTrackPreferenceForEngineSwitch()
    Log.w(PlayerRuntimeController.TAG, "Startup playback error; auto-switching engine: $detailedError")
    startupEngineFailoverTriggered = true

    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_message, targetEngineLabel)

    hidePlayerEngineSwitchInfoJob?.cancel()
    showRecoveryOverlay()
    _uiState.update {
        it.copy(
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage
        )
    }

    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = switchingToMpv
    delayMpvResumeSeekUntilVideoTrack = switchingToMpv
    releasePlayer(flushPlaybackState = false)
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = targetEngine,
        allowEngineFailover = false
    )
    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }

    return true
}

internal fun PlayerRuntimeController.switchInternalPlayerEngineManually() {
    if (currentStreamUrl.isBlank()) return

    val targetEngine = when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
    }
    beginSwitchTraceSession(reason = "manual-osd", targetEngine = targetEngine)
    val targetEngineLabel = targetEngineLabel(targetEngine)
    val switchMessage = context.getString(R.string.player_engine_switching_manual_message, targetEngineLabel)
    val currentPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    logSwitchTrace(
        stage = "manual-switch-trigger",
        message = "targetEngine=$targetEngine positionMs=$currentPosition " +
            "audioUiIndex=${_uiState.value.selectedAudioTrackIndex} subtitleUiIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )

    rememberCurrentTrackPreferenceForEngineSwitch()

    if (currentPosition > 0L) {
        pendingResumeProgress = null
        _uiState.update { it.copy(pendingSeekPosition = currentPosition) }
    }

    startupEngineFailoverTriggered = false
    userPausedManually = false
    resetErrorRetryState()
    hidePlayerEngineSwitchInfoJob?.cancel()
    _uiState.update {
        it.copy(
            error = null,
            showPauseOverlay = false,
            showLoadingOverlay = it.loadingOverlayEnabled,
            showControls = false,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            internalPlayerEngine = targetEngine,
            showPlayerEngineSwitchInfo = true,
            playerEngineSwitchInfoText = switchMessage
        )
    }

    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    pendingMpvHardRestartOnNextAttach = switchingToMpv
    delayMpvResumeSeekUntilVideoTrack = switchingToMpv

    releasePlayer(flushPlaybackState = true)
    initializePlayer(
        url = currentStreamUrl,
        headers = currentHeaders,
        overrideInternalPlayerEngine = targetEngine,
        allowEngineFailover = true
    )

    hidePlayerEngineSwitchInfoJob = scope.launch {
        delay(2200)
        _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
    }
}

private fun PlayerRuntimeController.isStartupPhaseForEngineFailover(): Boolean {
    val state = _uiState.value
    val currentPosition = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: playbackTimeline.value.currentPosition
    return !hasRenderedFirstFrame && (state.showLoadingOverlay || state.isBuffering || currentPosition <= 0L)
}

private fun PlayerRuntimeController.targetEngineLabel(targetEngine: InternalPlayerEngine): String {
    return when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
    }
}

internal fun PlayerRuntimeController.clearPendingEngineSwitchTrackPreference() {
    logSwitchTrace(
        stage = "pending-switch-pref-clear",
        message = "reason=explicit-clear previous=${pendingEngineSwitchTrackPreference != null}"
    )
    pendingEngineSwitchTrackPreference = null
}

private fun PlayerRuntimeController.rememberCurrentTrackPreferenceForEngineSwitch() {
    val state = _uiState.value
    val sourceEngine = currentInternalPlayerEngine
    logSwitchTrace(
        stage = "capture-start",
        message = "sourceEngine=$sourceEngine uiAudioIndex=${state.selectedAudioTrackIndex} " +
            "uiSubtitleIndex=${state.selectedSubtitleTrackIndex} " +
            "uiAudioCount=${state.audioTracks.size} uiSubtitleCount=${state.subtitleTracks.size} " +
            "uiAddonSelected=${state.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"}"
    )

    val rememberedAudio = captureCurrentAudioSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    val rememberedSubtitle = resolveCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )

    val mergedPreference = PlayerRuntimeController.TrackPreference(
        audio = rememberedAudio,
        subtitle = rememberedSubtitle
    )
    val capturedPreference = mergedPreference.takeUnless { it.audio == null && it.subtitle == null }
    pendingEngineSwitchTrackPreference = capturedPreference?.let { preference ->
        PlayerRuntimeController.PendingEngineSwitchTrackPreference(
            streamUrl = currentStreamUrl,
            preference = preference,
            sourceEngine = sourceEngine
        )
    }
    logSwitchTrace(
        stage = "capture-finish",
        message = "sourceEngine=$sourceEngine " +
            "audio=${describeRememberedTrackForLog(rememberedAudio)} " +
            "subtitle=${describeRememberedSubtitleForLog(rememberedSubtitle)} " +
            "savedForSwitch=${capturedPreference != null}"
    )
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
}

private fun PlayerRuntimeController.captureCurrentAudioSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedTrackSelection? {
    val mpvSelected = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        mpvView?.readTrackSnapshot()?.audioTracks?.firstOrNull { it.isSelected }
    } else {
        null
    }
    if (mpvSelected != null) {
        logSwitchTrace(
            stage = "capture-audio",
            message = "source=mpv-snapshot lang=${mpvSelected.language} name=${mpvSelected.name} id=${mpvSelected.id}"
        )
        return PlayerRuntimeController.RememberedTrackSelection(
            language = mpvSelected.language,
            name = mpvSelected.name,
            trackId = mpvSelected.id.toString()
        )
    }

    val exoSelected = run {
        val player = _exoPlayer ?: return@run null
        player.currentTracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val format = group.getTrackFormat(i)
                return@run PlayerRuntimeController.RememberedTrackSelection(
                    language = format.language,
                    name = format.label ?: format.language ?: "Audio",
                    trackId = format.id
                )
            }
        }
        null
    }
    if (exoSelected != null) {
        logSwitchTrace(
            stage = "capture-audio",
            message = "source=exo-tracks lang=${exoSelected.language} name=${exoSelected.name} id=${exoSelected.trackId}"
        )
        return exoSelected
    }

    val uiTrack = state.audioTracks.getOrNull(state.selectedAudioTrackIndex)
        ?: state.audioTracks.firstOrNull { it.isSelected }

    return uiTrack?.let { track ->
        logSwitchTrace(
            stage = "capture-audio",
            message = "source=ui-fallback lang=${track.language} name=${track.name} id=${track.trackId}"
        )
        PlayerRuntimeController.RememberedTrackSelection(
            language = track.language,
            name = track.name,
            trackId = track.trackId
        )
    }.also { selection ->
        if (selection == null) {
            logSwitchTrace(
                stage = "capture-audio",
                message = "source=none result=null"
            )
        }
    }
}

private fun PlayerRuntimeController.captureCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    data class SelectedExoTextTrack(
        val id: String?,
        val language: String?,
        val label: String?,
        val isForced: Boolean,
        val indexHint: Int?,
        val languageIndexHint: Int?
    )

    val mpvSnapshot = if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) mpvView?.readTrackSnapshot() else null
    logSwitchTrace(
        stage = "capture-subtitle-start",
        message = "sourceEngine=$sourceEngine uiSubtitleCount=${state.subtitleTracks.size} " +
            "uiSelectedSubtitleIndex=${state.selectedSubtitleTrackIndex} " +
            "uiAddonSelected=${state.selectedAddonSubtitle?.let { "${it.lang}/${it.addonName}/${it.id}" } ?: "none"} " +
            "mpvSnapshotSubs=${mpvSnapshot?.subtitleTracks?.size ?: -1}"
    )
    if (sourceEngine == InternalPlayerEngine.MVP_PLAYER) {
        val mpvSelectedSubtitle = mpvSnapshot?.subtitleTracks?.firstOrNull { it.isSelected }
        if (mpvSelectedSubtitle != null) {
            if (mpvSelectedSubtitle.isExternal) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = mpvSelectedSubtitle.name,
                    language = mpvSelectedSubtitle.language
                )
                if (addon != null) {
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=mpv-external->addon id=${addon.id} lang=${addon.lang} addon=${addon.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=mpv-external->ui-addon id=${selectedAddonFromUi.id} " +
                            "lang=${selectedAddonFromUi.lang} addon=${selectedAddonFromUi.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=mpv-selected-internal id=${mpvSelectedSubtitle.id} lang=${mpvSelectedSubtitle.language} " +
                    "name=${mpvSelectedSubtitle.name} forced=${mpvSelectedSubtitle.isForced} external=${mpvSelectedSubtitle.isExternal}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = mpvSelectedSubtitle.language,
                    name = mpvSelectedSubtitle.name,
                    trackId = mpvSelectedSubtitle.id.toString(),
                    isForced = mpvSelectedSubtitle.isForced,
                    selectedUiTrackOverride = resolveUiSubtitleTrackForEngineSwitchHints(
                        state = state,
                        trackId = mpvSelectedSubtitle.id.toString(),
                        language = mpvSelectedSubtitle.language,
                        name = mpvSelectedSubtitle.name
                    ),
                    allowUiStateFallbackForHints = false
                )
            )
        }
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=mpv-selected-internal result=none"
        )
    }

    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val exoPlayer = _exoPlayer
        val exoTextTrackDisabled =
            exoPlayer?.trackSelectionParameters?.disabledTrackTypes?.contains(C.TRACK_TYPE_TEXT) == true
        logSwitchTrace(
            stage = "capture-subtitle-exo",
            message = "textTrackDisabled=$exoTextTrackDisabled hasPlayer=${exoPlayer != null}"
        )
        val exoSelectedText = run {
            val player = exoPlayer ?: return@run null
            val internalTracks = mutableListOf<SelectedExoTextTrack>()
            var selectedTrack: SelectedExoTextTrack? = null
            player.currentTracks.groups.forEach { group ->
                if (group.type != C.TRACK_TYPE_TEXT) return@forEach
                for (i in 0 until group.length) {
                    val format = group.getTrackFormat(i)
                    val trackId = format.id
                    val isForced = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val isAddonTrack =
                        trackId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true
                    val currentTrack = SelectedExoTextTrack(
                        id = trackId,
                        language = format.language,
                        label = format.label,
                        isForced = isForced,
                        indexHint = if (isAddonTrack) null else internalTracks.size,
                        languageIndexHint = null
                    )
                    if (!isAddonTrack) {
                        internalTracks += currentTrack
                    }
                    if (group.isTrackSelected(i)) {
                        selectedTrack = currentTrack
                    }
                }
            }
            val selected = selectedTrack ?: run {
                val preferredTargets = subtitleLanguageTargets()
                internalTracks.firstOrNull { track ->
                    preferredTargets.any { target ->
                        val normalizedTarget = target.trim().lowercase()
                        if (normalizedTarget == "forced") {
                            track.isForced
                        } else {
                            PlayerSubtitleUtils.matchesLanguageCode(track.language, target)
                        }
                    }
                } ?: internalTracks.firstOrNull { !it.isForced }
                    ?: internalTracks.firstOrNull()
            } ?: return@run null
            val selectedIndex = selected.indexHint ?: return@run selected
            val selectedInternalTrack = internalTracks.getOrNull(selectedIndex) ?: return@run selected
            val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                language = selectedInternalTrack.language,
                name = selectedInternalTrack.label ?: selectedInternalTrack.language,
                trackId = selectedInternalTrack.id
            )
            val languageCandidates = internalTracks.indices.filter { index ->
                val candidate = internalTracks[index]
                val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = candidate.language,
                    name = candidate.label ?: candidate.language,
                    trackId = candidate.id
                )
                candidateVariant == selectedVariant ||
                    (!selectedInternalTrack.language.isNullOrBlank() &&
                        PlayerSubtitleUtils.matchesLanguageCode(
                            candidate.language,
                            selectedInternalTrack.language
                        ))
            }
            selected.copy(
                languageIndexHint = languageCandidates.indexOf(selectedIndex).takeIf { it >= 0 }
            )
        }
        if (exoSelectedText == null && exoTextTrackDisabled) {
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=exo-selected result=disabled(no-selected-and-disabled=true)"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        }
        if (exoSelectedText != null) {
            val formatId = exoSelectedText.id
            val formatLanguage = exoSelectedText.language
            val formatLabel = exoSelectedText.label
            if (formatId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) {
                val addon = findAddonSubtitleByTrackIdOrLanguage(
                    state = state,
                    trackId = formatId,
                    language = formatLanguage
                )
                if (addon != null) {
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=exo-addon-track->addon id=${addon.id} lang=${addon.lang} addon=${addon.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = addon.id,
                        url = addon.url,
                        language = addon.lang,
                        addonName = addon.addonName
                    )
                }
                state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
                    logSwitchTrace(
                        stage = "capture-subtitle",
                        message = "source=exo-addon-track->ui-addon id=${selectedAddonFromUi.id} " +
                            "lang=${selectedAddonFromUi.lang} addon=${selectedAddonFromUi.addonName}"
                    )
                    return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
                        id = selectedAddonFromUi.id,
                        url = selectedAddonFromUi.url,
                        language = selectedAddonFromUi.lang,
                        addonName = selectedAddonFromUi.addonName
                    )
                }
            }

            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=exo-selected-internal id=$formatId lang=$formatLanguage name=$formatLabel " +
                    "indexHint=${exoSelectedText.indexHint} languageIndexHint=${exoSelectedText.languageIndexHint} " +
                    "forced=${exoSelectedText.isForced}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = PlayerRuntimeController.RememberedTrackSelection(
                    language = formatLanguage,
                    name = formatLabel ?: formatLanguage ?: "Subtitle",
                    trackId = formatId,
                    indexHint = exoSelectedText.indexHint,
                    languageIndexHint = exoSelectedText.languageIndexHint,
                    isForcedHint = exoSelectedText.isForced
                )
            )
        }
    }

    val uiTrack = state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
        ?: state.subtitleTracks.firstOrNull { it.isSelected }
    if (uiTrack != null) {
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=ui-track id=${uiTrack.trackId} lang=${uiTrack.language} name=${uiTrack.name} forced=${uiTrack.isForced}"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                state = state,
                language = uiTrack.language,
                name = uiTrack.name,
                trackId = uiTrack.trackId,
                isForced = uiTrack.isForced
            )
        )
    }
    if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        val uiFallbackTrack = pickUiInternalSubtitleTrackForEngineSwitchFallback(state)
        if (uiFallbackTrack != null) {
            logSwitchTrace(
                stage = "capture-subtitle",
                message = "source=ui-fallback id=${uiFallbackTrack.trackId} lang=${uiFallbackTrack.language} " +
                    "name=${uiFallbackTrack.name} forced=${uiFallbackTrack.isForced}"
            )
            return PlayerRuntimeController.RememberedSubtitleSelection.Internal(
                track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
                    state = state,
                    language = uiFallbackTrack.language,
                    name = uiFallbackTrack.name,
                    trackId = uiFallbackTrack.trackId,
                    isForced = uiFallbackTrack.isForced,
                    selectedUiTrackOverride = uiFallbackTrack
                )
            )
        }
    }

    state.selectedAddonSubtitle?.let { selectedAddonFromUi ->
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=ui-selected-addon id=${selectedAddonFromUi.id} " +
                "lang=${selectedAddonFromUi.lang} addon=${selectedAddonFromUi.addonName}"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = selectedAddonFromUi.id,
            url = selectedAddonFromUi.url,
            language = selectedAddonFromUi.lang,
            addonName = selectedAddonFromUi.addonName
        )
    }

    val mpvHasSubtitleTracks = mpvSnapshot?.subtitleTracks?.isNotEmpty() == true
    val exoHasSubtitleTracks = if (sourceEngine == InternalPlayerEngine.EXOPLAYER) {
        run {
            val player = _exoPlayer ?: return@run false
            player.currentTracks.groups.any { group ->
                group.type == C.TRACK_TYPE_TEXT && group.length > 0
            }
        }
    } else {
        false
    }
    if (mpvHasSubtitleTracks || exoHasSubtitleTracks) {
        logSwitchTrace(
            stage = "capture-subtitle",
            message = "source=implicit-disabled mpvHasTracks=$mpvHasSubtitleTracks exoHasTracks=$exoHasSubtitleTracks"
        )
        return PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    }

    logSwitchTrace(
        stage = "capture-subtitle",
        message = "source=none result=null"
    )
    return null
}

private fun PlayerRuntimeController.resolveCurrentSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    sourceEngine: InternalPlayerEngine
): PlayerRuntimeController.RememberedSubtitleSelection? {
    val capturedSelection = captureCurrentSubtitleSelectionForEngineSwitch(
        state = state,
        sourceEngine = sourceEngine
    )
    logSwitchTrace(
        stage = "resolve-subtitle-start",
        message = "captured=${describeRememberedSubtitleForLog(capturedSelection)} sourceEngine=$sourceEngine"
    )
    val shouldUseFallback =
        capturedSelection == null || capturedSelection == PlayerRuntimeController.RememberedSubtitleSelection.Disabled
    if (!shouldUseFallback) {
        logSwitchTrace(
            stage = "resolve-subtitle",
            message = "result=${describeRememberedSubtitleForLog(capturedSelection)} reason=captured-direct"
        )
        return capturedSelection
    }

    effectiveSubtitleSelectionForEngineSwitch
        ?.takeIf { effective -> effective.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { effectiveSelection ->
            logSwitchTrace(
                stage = "resolve-subtitle",
                message = "result=${describeRememberedSubtitleForLog(effectiveSelection)} " +
                    "reason=effective-fallback captured=${describeRememberedSubtitleForLog(capturedSelection)}"
            )
            return effectiveSelection
        }

    explicitSubtitleSelectionForEngineSwitch
        ?.takeIf { explicit -> explicit.streamUrl == currentStreamUrl }
        ?.selection
        ?.let { explicitSelection ->
            logSwitchTrace(
                stage = "resolve-subtitle",
                message = "result=${describeRememberedSubtitleForLog(explicitSelection)} " +
                    "reason=explicit-fallback captured=${describeRememberedSubtitleForLog(capturedSelection)}"
            )
            return explicitSelection
        }

    rememberedTrackPreference?.subtitle?.let { rememberedSelection ->
        logSwitchTrace(
            stage = "resolve-subtitle",
            message = "result=${describeRememberedSubtitleForLog(rememberedSelection)} " +
                "reason=remembered-fallback captured=${describeRememberedSubtitleForLog(capturedSelection)}"
        )
        return rememberedSelection
    }

    logSwitchTrace(
        stage = "resolve-subtitle",
        message = "result=${describeRememberedSubtitleForLog(capturedSelection)} reason=no-fallback-available"
    )
    return capturedSelection
}

private fun PlayerRuntimeController.pickUiInternalSubtitleTrackForEngineSwitchFallback(
    state: PlayerUiState
): TrackInfo? {
    if (state.subtitleTracks.isEmpty()) {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "result=null reason=no-ui-tracks"
        )
        return null
    }

    state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=selectedSubtitleTrackIndex index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }
    state.subtitleTracks.firstOrNull { it.isSelected }?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-isSelected index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }

    val preferredIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = subtitleLanguageTargets()
    )
    if (preferredIndex >= 0) {
        state.subtitleTracks.getOrNull(preferredIndex)?.let {
            logSwitchTrace(
                stage = "capture-subtitle-ui-fallback",
                message = "reason=preferred-language index=${it.index} id=${it.trackId} lang=${it.language}"
            )
            return it
        }
    }

    state.subtitleTracks.firstOrNull { !it.isForced }?.let {
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-non-forced index=${it.index} id=${it.trackId} lang=${it.language}"
        )
        return it
    }
    return state.subtitleTracks.firstOrNull().also { selected ->
        logSwitchTrace(
            stage = "capture-subtitle-ui-fallback",
            message = "reason=first-any index=${selected?.index} id=${selected?.trackId} lang=${selected?.language}"
        )
    }
}

internal fun PlayerRuntimeController.buildRememberedInternalSubtitleSelectionForEngineSwitch(
    state: PlayerUiState,
    language: String?,
    name: String?,
    trackId: String?,
    isForced: Boolean,
    selectedUiTrackOverride: TrackInfo? = null,
    allowUiStateFallbackForHints: Boolean = true
): PlayerRuntimeController.RememberedTrackSelection {
    val selectedUiTrack = selectedUiTrackOverride
        ?: if (allowUiStateFallbackForHints) {
            state.subtitleTracks.getOrNull(state.selectedSubtitleTrackIndex)
                ?: state.subtitleTracks.firstOrNull { it.isSelected }
        } else {
            null
        }

    val selection = PlayerRuntimeController.RememberedTrackSelection(
        language = language,
        name = name,
        trackId = trackId,
        indexHint = selectedUiTrack?.index?.takeIf { it >= 0 },
        languageIndexHint = selectedUiTrack?.let { track ->
            subtitleLanguageOrdinalHintForEngineSwitch(state.subtitleTracks, track)
        },
        isForcedHint = selectedUiTrack?.isForced ?: isForced
    )
    logSwitchTrace(
        stage = "capture-subtitle-hints-build",
        message = "inputId=$trackId inputLang=$language inputName=$name inputForced=$isForced " +
            "uiTrack=${selectedUiTrack?.let { "${it.index}/${it.trackId}/${it.language}/${it.name}/forced=${it.isForced}" } ?: "none"} " +
            "result=${describeRememberedTrackForLog(selection)} allowUiFallback=$allowUiStateFallbackForHints"
    )
    return selection
}

private fun PlayerRuntimeController.resolveUiSubtitleTrackForEngineSwitchHints(
    state: PlayerUiState,
    trackId: String?,
    language: String?,
    name: String?
): TrackInfo? {
    val normalizedTrackId = normalizeTrackMatchValue(trackId)
    if (!normalizedTrackId.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.trackId) == normalizedTrackId
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-hints",
                message = "reason=track-id id=${it.trackId} lang=${it.language} name=${it.name}"
            )
            return it
        }
    }

    val normalizedLanguage = normalizeTrackMatchValue(language)
    val normalizedName = normalizeTrackMatchValue(name)

    if (!normalizedLanguage.isNullOrBlank() && !normalizedName.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage &&
                normalizeTrackMatchValue(track.name) == normalizedName
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-hints",
                message = "reason=lang+name id=${it.trackId} lang=${it.language} name=${it.name}"
            )
            return it
        }
    }

    if (!normalizedLanguage.isNullOrBlank()) {
        state.subtitleTracks.firstOrNull { track ->
            normalizeTrackMatchValue(track.language) == normalizedLanguage && track.isSelected
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-hints",
                message = "reason=lang+selected id=${it.trackId} lang=${it.language} name=${it.name}"
            )
            return it
        }
    }

    logSwitchTrace(
        stage = "capture-subtitle-hints",
        message = "reason=no-ui-match trackId=$trackId language=$language name=$name"
    )
    return null
}

internal fun PlayerRuntimeController.subtitleLanguageOrdinalHintForEngineSwitch(
    tracks: List<TrackInfo>,
    selectedTrack: TrackInfo
): Int? {
    val selectedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = selectedTrack.language,
        name = selectedTrack.name,
        trackId = selectedTrack.trackId
    )
    val selectedTrackPosition = tracks.indexOfFirst { it.index == selectedTrack.index }
    if (selectedTrackPosition < 0) return null

    val candidates = tracks.indices.filter { index ->
        val candidate = tracks[index]
        val candidateVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = candidate.language,
            name = candidate.name,
            trackId = candidate.trackId
        )
        candidateVariant == selectedVariant ||
            (!selectedTrack.language.isNullOrBlank() &&
                PlayerSubtitleUtils.matchesLanguageCode(candidate.language, selectedTrack.language))
    }

    val ordinal = candidates.indexOf(selectedTrackPosition).takeIf { it >= 0 }
    logSwitchTrace(
        stage = "capture-subtitle-language-ordinal",
        message = "selectedIndex=${selectedTrack.index} selectedId=${selectedTrack.trackId} " +
            "selectedLang=${selectedTrack.language} candidates=$candidates ordinal=$ordinal"
    )
    return ordinal
}

private fun PlayerRuntimeController.findAddonSubtitleByTrackIdOrLanguage(
    state: PlayerUiState,
    trackId: String?,
    language: String?
): com.nuvio.tv.domain.model.Subtitle? {
    val normalizedTrackId = trackId?.trim()
    if (!normalizedTrackId.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            val addonTrackId = buildAddonSubtitleTrackId(subtitle)
            addonTrackId.equals(normalizedTrackId, ignoreCase = true) ||
                normalizedTrackId.contains(addonTrackId, ignoreCase = true)
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-addon-match",
                message = "reason=trackId matchId=${it.id} matchLang=${it.lang} addon=${it.addonName} trackId=$trackId"
            )
            return it
        }
    }
    val lang = language?.trim()
    if (!lang.isNullOrBlank()) {
        state.addonSubtitles.firstOrNull { subtitle ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, lang)
        }?.let {
            logSwitchTrace(
                stage = "capture-subtitle-addon-match",
                message = "reason=language matchId=${it.id} matchLang=${it.lang} addon=${it.addonName} language=$language"
            )
            return it
        }
    }
    logSwitchTrace(
        stage = "capture-subtitle-addon-match",
        message = "reason=no-match trackId=$trackId language=$language addonPool=${state.addonSubtitles.size}"
    )
    return null
}

private fun PlayerRuntimeController.describeRememberedSubtitleForLog(
    selection: PlayerRuntimeController.RememberedSubtitleSelection?
): String {
    return when (selection) {
        null -> "none"
        PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "disabled"
        is PlayerRuntimeController.RememberedSubtitleSelection.Internal ->
            "internal:${describeRememberedTrackForLog(selection.track)}"
        is PlayerRuntimeController.RememberedSubtitleSelection.Addon ->
            "addon:${selection.language}/${selection.addonName}/${selection.id}"
    }
}

private fun PlayerRuntimeController.describeRememberedTrackForLog(
    selection: PlayerRuntimeController.RememberedTrackSelection?
): String {
    if (selection == null) return "none"
    return "lang=${selection.language} name=${selection.name} trackId=${selection.trackId} " +
        "indexHint=${selection.indexHint} languageIndexHint=${selection.languageIndexHint} " +
        "forcedHint=${selection.isForcedHint}"
}
