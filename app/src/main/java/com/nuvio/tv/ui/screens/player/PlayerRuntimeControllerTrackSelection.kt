package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal fun PlayerRuntimeController.filterEpisodeStreamsByAddon(addonName: String?) {
    val allStreams = _uiState.value.episodeAllStreams
    val filteredStreams = if (addonName == null) {
        allStreams
    } else {
        allStreams.filter { it.addonName == addonName }
    }

    _uiState.update {
        it.copy(
            episodeSelectedAddonFilter = addonName,
            episodeFilteredStreams = filteredStreams
        )
    }
}

internal fun PlayerRuntimeController.showControlsTemporarily() {
    hideSeekOverlayJob?.cancel()
    _uiState.update { it.copy(showControls = true, showSeekOverlay = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.showSeekOverlayTemporarily() {
    hideSeekOverlayJob?.cancel()
    _uiState.update { it.copy(showSeekOverlay = true) }
    hideSeekOverlayJob = scope.launch {
        delay(1500)
        _uiState.update { it.copy(showSeekOverlay = false) }
    }
}

internal fun PlayerRuntimeController.selectAudioTrack(trackIndex: Int) {
    logSwitchTrace(
        stage = "select-audio-track",
        message = "trackIndex=$trackIndex usingMpv=${isUsingMpvEngine()} " +
            "track=${_uiState.value.audioTracks.getOrNull(trackIndex)?.let { "${it.language}/${it.name}/${it.trackId}" } ?: "none"}"
    )
    if (isUsingMpvEngine()) {
        val wasPlaying = isPlaybackCurrentlyPlaying()
        val track = _uiState.value.audioTracks.getOrNull(trackIndex)
        val trackId = track?.trackId?.toIntOrNull()
        val changed = trackId != null && mpvView?.selectAudioTrackById(trackId) == true
        if (changed) {
            keepMpvPlayingIfNeeded(wasPlaying)
        }
        return
    }

    _exoPlayer?.let { player ->
        val tracks = player.currentTracks
        var currentAudioIndex = 0
        
        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_AUDIO) {
                for (i in 0 until trackGroup.length) {
                    if (currentAudioIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .build()
                        // Nudge the player to avoid infinite buffering after audio track switch
                        // where the new track requires a different segment.
                        val pos = player.currentPosition
                        if (pos > 0) player.seekTo((pos - 1).coerceAtLeast(0))
                        return
                    }
                    currentAudioIndex++
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.rememberAudioSelection(trackIndex: Int) {
    val selectedTrack = _uiState.value.audioTracks.getOrNull(trackIndex) ?: return
    logSwitchTrace(
        stage = "user-remember-audio",
        message = "trackIndex=$trackIndex lang=${selectedTrack.language} name=${selectedTrack.name} id=${selectedTrack.trackId}"
    )
    val basePreference = currentTrackPreferenceForPersistence()
    clearPendingEngineSwitchTrackPreference()
    persistedTrackPreference = null
    rememberedTrackPreference =
        basePreference
            .copy(
                audio = PlayerRuntimeController.RememberedTrackSelection(
                    language = selectedTrack.language,
                    name = selectedTrack.name,
                    trackId = selectedTrack.trackId
                )
            )
    persistTrackPreference()
}

/**
 * True when [formatId] identifies the same sidecar track as [addonTrackId].
 *
 * Media3 usually preserves [MediaItem.SubtitleConfiguration] ids on text
 * [Format]s, but some merge/sidecar paths truncate or rewrite them. Prefer
 * exact / contains matches, then a unique soft match on the addon subtitle id
 * portion (`nuvio-addon-sub:<subtitleId>:…`).
 */
private fun formatMatchesAddonTrackId(formatId: String?, addonTrackId: String): Boolean {
    if (formatId.isNullOrBlank() || addonTrackId.isBlank()) return false
    if (formatId == addonTrackId) return true
    if (formatId.contains(addonTrackId)) return true
    // Truncated Format.id that is still a prefix of our full track id.
    if (
        formatId.startsWith(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) &&
        addonTrackId.startsWith(formatId)
    ) {
        return true
    }
    return false
}

private fun addonSubtitleIdFromTrackId(addonTrackId: String): String? {
    if (!addonTrackId.startsWith(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX)) {
        return null
    }
    val remainder = addonTrackId.removePrefix(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX)
    // track id is "<subtitleId>:<urlHash>"; subtitleId itself may contain ':' so
    // strip only the trailing hash segment.
    val hashSeparator = remainder.lastIndexOf(':')
    if (hashSeparator <= 0) return remainder.takeIf { it.isNotBlank() }
    return remainder.substring(0, hashSeparator).takeIf { it.isNotBlank() }
}

private fun PlayerRuntimeController.applyTextTrackOverride(
    trackGroup: Tracks.Group,
    trackIndexInGroup: Int
): Boolean {
    val player = _exoPlayer ?: return false
    val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, trackIndexInGroup)
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setOverrideForType(override)
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
        .build()
    return true
}

private data class AddonTextTrackCandidate(
    val group: Tracks.Group,
    val index: Int,
    val formatId: String?
)

internal fun PlayerRuntimeController.applyAddonSubtitleOverride(addonTrackId: String): Boolean {
    val player = _exoPlayer ?: return false

    val strict = mutableListOf<AddonTextTrackCandidate>()
    val soft = mutableListOf<AddonTextTrackCandidate>()
    val softSubtitleId = addonSubtitleIdFromTrackId(addonTrackId)

    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(i)
            val formatId = format.id
            if (formatMatchesAddonTrackId(formatId, addonTrackId)) {
                strict += AddonTextTrackCandidate(trackGroup, i, formatId)
                continue
            }
            // Soft match only when Format.id is exactly our prefix+subtitleId, or that
            // prefix followed by ":…". Avoid bare contains() on short ids like "en".
            if (softSubtitleId != null && formatId != null) {
                val softPrefix =
                    PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX + softSubtitleId
                if (formatId == softPrefix || formatId.startsWith("$softPrefix:")) {
                    soft += AddonTextTrackCandidate(trackGroup, i, formatId)
                }
            }
        }
    }

    val chosen = when {
        strict.size == 1 -> strict[0]
        strict.size > 1 -> {
            // Prefer exact id equality when multiple contain-matches fire.
            strict.firstOrNull { it.formatId == addonTrackId } ?: strict[0]
        }
        soft.size == 1 -> soft[0]
        else -> null
    }
    val chosenFromSoft = chosen != null && strict.isEmpty()

    if (chosen != null) {
        applyTextTrackOverride(chosen.group, chosen.index)
        Log.d(
            PlayerRuntimeController.TAG,
            "applyAddonSubtitleOverride: found id=${chosen.formatId} at group/track ${chosen.index} " +
                "targetId=$addonTrackId soft=$chosenFromSoft"
        )
        return true
    }

    Log.d(
        PlayerRuntimeController.TAG,
        "applyAddonSubtitleOverride: track not found yet for id=$addonTrackId " +
            "(strict=${strict.size}, soft=${soft.size})"
    )
    return false
}

/**
 * Language-only addon override. Safe only when a single addon text track matches
 * [language], or when [preferredTrackId] uniquely identifies the target.
 *
 * Never pick the first same-language track: preferred-language startup attaches
 * every matching addon (OpenSubtitles + SubMaker + …), and first-match would
 * keep the original auto-pick while the UI shows the user's choice (#2601).
 */
internal fun PlayerRuntimeController.applyAddonSubtitleOverrideByLanguage(
    language: String,
    preferredTrackId: String? = null
): Boolean {
    val player = _exoPlayer ?: return false

    val preferred = mutableListOf<AddonTextTrackCandidate>()
    val languageMatches = mutableListOf<AddonTextTrackCandidate>()

    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (i in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(i)
            val formatId = format.id
            if (formatId?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) != true) {
                continue
            }
            if (!preferredTrackId.isNullOrBlank() && formatMatchesAddonTrackId(formatId, preferredTrackId)) {
                preferred += AddonTextTrackCandidate(trackGroup, i, formatId)
                continue
            }
            if (!PlayerSubtitleUtils.matchesLanguageCode(format.language, language)) {
                continue
            }
            languageMatches += AddonTextTrackCandidate(trackGroup, i, formatId)
        }
    }

    val chosen = when {
        preferred.isNotEmpty() -> preferred[0]
        languageMatches.size == 1 -> languageMatches[0]
        else -> null
    }

    if (chosen != null) {
        applyTextTrackOverride(chosen.group, chosen.index)
        Log.d(
            PlayerRuntimeController.TAG,
            "applyAddonSubtitleOverrideByLanguage: found id=${chosen.formatId} lang=$language " +
                "at group/track ${chosen.index} preferredTrackId=$preferredTrackId " +
                "languageMatches=${languageMatches.size}"
        )
        return true
    }

    Log.d(
        PlayerRuntimeController.TAG,
        "applyAddonSubtitleOverrideByLanguage: no unique track for language=$language " +
            "preferredTrackId=$preferredTrackId languageMatches=${languageMatches.size}"
    )
    return false
}

internal fun PlayerRuntimeController.selectSubtitleTrack(trackIndex: Int) {
    logSwitchTrace(
        stage = "select-subtitle-track",
        message = "trackIndex=$trackIndex usingMpv=${isUsingMpvEngine()} " +
            "track=${_uiState.value.subtitleTracks.getOrNull(trackIndex)?.let { "${it.language}/${it.name}/${it.trackId}/forced=${it.isForced}" } ?: "none"}"
    )
    if (isUsingMpvEngine()) {
        Log.d(PlayerRuntimeController.TAG, "Selecting INTERNAL subtitle trackIndex=$trackIndex (mpv)")
        val shouldKeepPlaying = !userPausedManually && !_uiState.value.playbackEnded
        val track = _uiState.value.subtitleTracks.getOrNull(trackIndex)
        val trackId = track?.trackId?.toIntOrNull()
        val changed = trackId != null && mpvView?.selectSubtitleTrackById(trackId) == true
        if (changed) {
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            updateMpvAvailableTracks()
            keepMpvPlayingIfNeeded(shouldKeepPlaying)
        }
        return
    }

    _exoPlayer?.let { player ->
        Log.d(PlayerRuntimeController.TAG, "Selecting INTERNAL subtitle trackIndex=$trackIndex")
        val tracks = player.currentTracks
        var currentSubIndex = 0
        
        tracks.groups.forEach { trackGroup ->
            if (trackGroup.type == C.TRACK_TYPE_TEXT) {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
                    if (currentSubIndex == trackIndex) {
                        val override = TrackSelectionOverride(trackGroup.mediaTrackGroup, i)
                        player.trackSelectionParameters = player.trackSelectionParameters
                            .buildUpon()
                            .setOverrideForType(override)
                            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                            .build()
                        return
                    }
                    currentSubIndex++
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.rememberInternalSubtitleSelection(trackIndex: Int) {
    val selectedTrack = _uiState.value.subtitleTracks.getOrNull(trackIndex) ?: return
    logSwitchTrace(
        stage = "user-remember-subtitle-internal",
        message = "trackIndex=$trackIndex lang=${selectedTrack.language} name=${selectedTrack.name} " +
            "id=${selectedTrack.trackId} forced=${selectedTrack.isForced}"
    )
    val rememberedSelection = PlayerRuntimeController.RememberedSubtitleSelection.Internal(
        track = buildRememberedInternalSubtitleSelectionForEngineSwitch(
            state = _uiState.value,
            language = selectedTrack.language,
            name = selectedTrack.name,
            trackId = selectedTrack.trackId,
            isForced = selectedTrack.isForced,
            selectedUiTrackOverride = selectedTrack
        )
    )
    val basePreference = currentTrackPreferenceForPersistence()
    clearPendingEngineSwitchTrackPreference()
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    explicitSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = rememberedSelection
        )
    effectiveSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = rememberedSelection
        )
    rememberedTrackPreference =
        basePreference
            .copy(subtitle = rememberedSelection)
    persistTrackPreference()
}

internal fun PlayerRuntimeController.disableSubtitles() {
    resetSubtitleAutoSyncState()
    logSwitchTrace(
        stage = "disable-subtitles",
        message = "usingMpv=${isUsingMpvEngine()} selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex}"
    )
    if (isUsingMpvEngine()) {
        if (mpvView?.disableSubtitles() == true) {
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null
            _uiState.update {
                it.copy(
                    selectedAddonSubtitle = null,
                    selectedSubtitleTrackIndex = -1
                )
            }
            updateMpvAvailableTracks()
        }
        return
    }
    _exoPlayer?.let { player ->
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            .build()
    }
}

internal fun PlayerRuntimeController.refreshActiveSubtitleTrackAfterTimingChange() {
    // MPV applies delay via setSubtitleDelayMs; live Exo delay is applied by
    // SubtitleOffsetRenderer. This path only exists to flush any cue already on
    // screen under the previous offset. It must re-apply the active override
    // after re-enabling TEXT - otherwise the track stays selected in UI but
    // ExoPlayer has no TEXT override and subtitles disappear (issue #2710).
    if (isUsingMpvEngine()) return
    val player = _exoPlayer ?: return
    val state = _uiState.value
    if (state.selectedAddonSubtitle == null && state.selectedSubtitleTrackIndex < 0) return

    // Force a renderer reset so stale cues from the old delay do not linger on screen.
    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

    subtitleTimingRefreshJob?.cancel()
    subtitleTimingRefreshJob = scope.launch {
        delay(90)
        if (_exoPlayer !== player) return@launch
        val latestState = _uiState.value
        val latestAddon = latestState.selectedAddonSubtitle
        val latestInternalIndex = latestState.selectedSubtitleTrackIndex
        if (latestAddon == null && latestInternalIndex < 0) {
            return@launch
        }

        // Re-enable TEXT, then restore the same selection override that was active
        // before the bounce. setTrackTypeDisabled(false) alone is not enough when
        // the previous selection was applied via TrackSelectionOverride.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()

        if (latestAddon != null) {
            val trackId = buildAddonSubtitleTrackId(latestAddon)
            val restored = applyAddonSubtitleOverride(trackId) ||
                applyAddonSubtitleOverrideByLanguage(
                    language = PlayerSubtitleUtils.normalizeLanguageCode(latestAddon.lang),
                    preferredTrackId = trackId
                )
            if (!restored) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "refreshActiveSubtitleTrackAfterTimingChange: failed to restore addon " +
                        "subtitle id=${latestAddon.id} lang=${latestAddon.lang}"
                )
            }
        } else {
            selectSubtitleTrack(latestInternalIndex)
        }
    }
}

internal fun PlayerRuntimeController.rememberSubtitleDisabled() {
    logSwitchTrace(
        stage = "user-remember-subtitle-disabled",
        message = "selectedSubtitleIndex=${_uiState.value.selectedSubtitleTrackIndex} addonSelected=${_uiState.value.selectedAddonSubtitle != null}"
    )
    val basePreference = currentTrackPreferenceForPersistence()
    clearPendingEngineSwitchTrackPreference()
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    explicitSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        )
    effectiveSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        )
    rememberedTrackPreference =
        basePreference
            .copy(subtitle = PlayerRuntimeController.RememberedSubtitleSelection.Disabled)
    persistTrackPreference()
}

internal fun PlayerRuntimeController.buildAddonSubtitleTrackId(subtitle: Subtitle): String {
    val urlHashSuffix = subtitle.url.hashCode().toUInt().toString(16)
    return "${PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX}${subtitle.id}:$urlHashSuffix"
}

internal fun PlayerRuntimeController.isMpvAddonSubtitleTrackActive(
    subtitle: Subtitle
): Boolean {
    if (!isUsingMpvEngine()) return false

    val targetTrackId = buildAddonSubtitleTrackId(subtitle)
    return mpvView?.readTrackSnapshot()?.subtitleTracks?.any { track ->
        if (!track.isExternal || !track.isSelected) return@any false
        val normalizedTrackName = track.name.trim()
        normalizedTrackName.equals(targetTrackId, ignoreCase = true) ||
            normalizedTrackName.contains(targetTrackId, ignoreCase = true) ||
            targetTrackId.contains(normalizedTrackName, ignoreCase = true)
    } == true
}

internal fun PlayerRuntimeController.addonSubtitleKey(subtitle: Subtitle): String {
    return "${subtitle.id}|${subtitle.url}"
}

internal fun PlayerRuntimeController.toSubtitleConfiguration(subtitle: Subtitle): MediaItem.SubtitleConfiguration {
    val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
    val subtitleMimeType = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    val addonTrackId = buildAddonSubtitleTrackId(subtitle)
    
    val baseUri = android.net.Uri.parse(subtitle.url)
    val subtitleUri = baseUri.buildUpon()
        .appendQueryParameter("nuvio_type", "subtitle")
        .build()

    return MediaItem.SubtitleConfiguration.Builder(subtitleUri)
        .setId(addonTrackId)
        .setLanguage(normalizedLang)
        .setMimeType(subtitleMimeType)
        .setSelectionFlags(C.SELECTION_FLAG_DEFAULT)
        .build()
}

internal fun PlayerRuntimeController.selectAddonSubtitle(subtitle: Subtitle) {
    logSwitchTrace(
        stage = "select-addon-subtitle",
        message = "usingMpv=${isUsingMpvEngine()} addonId=${subtitle.id} addonLang=${subtitle.lang} addonName=${subtitle.addonName}"
    )
    if (isUsingMpvEngine()) {
        val currentlySelected = _uiState.value.selectedAddonSubtitle
        if (
            currentlySelected?.id == subtitle.id &&
            currentlySelected.url == subtitle.url &&
            isMpvAddonSubtitleTrackActive(subtitle)
        ) {
            return
        }
        Log.d(PlayerRuntimeController.TAG, "Selecting ADDON subtitle lang=${subtitle.lang} id=${subtitle.id} (mpv)")
        val wasPlaying = isPlaybackCurrentlyPlaying()
        val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        val trackTitle = buildAddonSubtitleTrackId(subtitle)
        val added = mpvView?.addAndSelectExternalSubtitle(
            url = subtitle.url,
            title = trackTitle,
            language = normalizedLang
        ) == true
        if (!added) return

        pendingAddonSubtitleLanguage = null
        pendingAddonSubtitleTrackId = null
        pendingAudioSelectionAfterSubtitleRefresh = null
        _uiState.update {
            it.copy(
                selectedAddonSubtitle = subtitle,
                selectedSubtitleTrackIndex = -1
            )
        }
        updateMpvAvailableTracks()
        keepMpvPlayingIfNeeded(wasPlaying)
        return
    }

    _exoPlayer?.let { player ->
        val currentlySelected = _uiState.value.selectedAddonSubtitle
        val addonTrackId = buildAddonSubtitleTrackId(subtitle)
        if (currentlySelected?.id == subtitle.id && currentlySelected.url == subtitle.url) {
            // Re-assert the player override if UI and Exo selection drifted (e.g. after a
            // preferred-language auto-pick left the wrong same-lang sidecar active).
            if (!applyAddonSubtitleOverride(addonTrackId)) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Re-selecting already-chosen ADDON subtitle; track not active yet id=${subtitle.id}"
                )
            }
            return@let
        }
        resetSubtitleAutoSyncState()
        val normalizedLang = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang)
        val inferredMime = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
        Log.d(
            PlayerRuntimeController.TAG,
            "Selecting ADDON subtitle addon=${subtitle.addonName} lang=${subtitle.lang} normalizedLang=$normalizedLang " +
                "id=${subtitle.id} inferredMime=$inferredMime " +
                "url=${subtitle.url}"
        )

        val preAttachedByStartup = attachedAddonSubtitleKeys.contains(addonSubtitleKey(subtitle))
        // Never fall back to language-only selection here. Preferred-language startup
        // attaches every matching addon; first-language-match would keep OpenSubtitles
        // (or whichever loaded first) while the UI shows SubMaker (#2601).
        val appliedWithoutReload = applyAddonSubtitleOverride(addonTrackId)

        if (appliedWithoutReload) {
            Log.d(
                PlayerRuntimeController.TAG,
                "Switching ADDON subtitle without media reload addon=${subtitle.addonName} id=${subtitle.id} " +
                    "trackId=$addonTrackId preAttached=$preAttachedByStartup"
            )
            pendingAddonSubtitleLanguage = null
            pendingAddonSubtitleTrackId = null
            pendingAudioSelectionAfterSubtitleRefresh = null

            _uiState.update {
                it.copy(
                    selectedAddonSubtitle = subtitle,
                    selectedSubtitleTrackIndex = -1
                )
            }
            return@let
        }

        pendingAddonSubtitleLanguage = normalizedLang
        pendingAddonSubtitleTrackId = addonTrackId
        pendingAudioSelectionAfterSubtitleRefresh =
            captureCurrentAudioSelectionForSubtitleRefresh(player)
        val subtitleConfigurations = (_uiState.value.addonSubtitles + subtitle)
            .distinctBy { "${it.id}|${it.url}" }
            .map(::toSubtitleConfiguration)
        Log.d(
            PlayerRuntimeController.TAG,
            "Selecting ADDON subtitle with media refresh addon=${subtitle.addonName} id=${subtitle.id} " +
                "attachedConfigs=${subtitleConfigurations.size} preAttached=$preAttachedByStartup"
        )
        attachedAddonSubtitleKeys = (_uiState.value.addonSubtitles + subtitle)
            .distinctBy { addonSubtitleKey(it) }
            .map(::addonSubtitleKey)
            .toSet()

        val currentPosition = player.currentPosition
        val playWhenReady = player.playWhenReady

        player.setMediaSource(
            mediaSourceFactory.createMediaSource(
                context = context,
                url = currentStreamUrl,
                headers = currentHeaders,
                subtitleConfigurations = subtitleConfigurations,
                filename = currentFilename,
                responseHeaders = currentStreamResponseHeaders,
                mimeTypeOverride = currentStreamMimeType,
                audioDelayUsProvider = audioDelayUs::get
            ),
            currentPosition
        )
        player.prepare()
        player.playWhenReady = playWhenReady

        // Clear any previous TEXT override so pendingAddonSubtitleTrackId can bind the
        // exact sidecar once tracks land. Preferred language alone is not enough when
        // multiple same-language addon tracks are attached.
        player.trackSelectionParameters = player.trackSelectionParameters
            .buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .setPreferredTextLanguage(normalizedLang)
            .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            .build()
        
        _uiState.update { 
            it.copy(
                selectedAddonSubtitle = subtitle,
                selectedSubtitleTrackIndex = -1 
            )
        }
    }
}


internal fun PlayerRuntimeController.rememberAddonSubtitleSelection(subtitle: Subtitle) {
    logSwitchTrace(
        stage = "user-remember-subtitle-addon",
        message = "addonId=${subtitle.id} addonLang=${subtitle.lang} addonName=${subtitle.addonName}"
    )
    val rememberedSelection = PlayerRuntimeController.RememberedSubtitleSelection.Addon(
        id = subtitle.id,
        url = subtitle.url,
        language = PlayerSubtitleUtils.normalizeLanguageCode(subtitle.lang),
        addonName = subtitle.addonName
    )
    val basePreference = currentTrackPreferenceForPersistence()
    clearPendingEngineSwitchTrackPreference()
    persistedTrackPreference = null
    subtitleDisabledByPersistedPreference = false
    subtitleAddonRestoredByPersistedPreference = false
    pendingRestoredAddonSubtitle = null
    explicitSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = rememberedSelection
        )
    effectiveSubtitleSelectionForEngineSwitch =
        PlayerRuntimeController.ExplicitSubtitleSelectionForEngineSwitch(
            streamUrl = currentStreamUrl,
            selection = rememberedSelection
        )
    rememberedTrackPreference =
        basePreference
            .copy(subtitle = rememberedSelection)
    persistTrackPreference()
}

private fun PlayerRuntimeController.currentTrackPreferenceForPersistence(): PlayerRuntimeController.TrackPreference {
    return rememberedTrackPreference ?: persistedTrackPreference ?: PlayerRuntimeController.TrackPreference()
}

internal fun PlayerRuntimeController.persistTrackPreference() {
    val id = contentId ?: return
    // Use the currently-effective preference (remembered OR previously persisted)
    // so that a delay-only change does not wipe a previously-saved track selection.
    // For the resume-from-CW case, rememberedTrackPreference is null for the fresh
    // session, and falling through to persistedTrackPreference preserves the user's
    // earlier audio/subtitle choices. See issue #1063.
    val pref = currentTrackPreferenceForPersistence()
    val audio = pref.audio
    val subtitle = pref.subtitle
    val persisted = com.nuvio.tv.data.local.PersistedTrackPreference(
        subtitleType = when (subtitle) {
            is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> "INTERNAL"
            is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> "ADDON"
            PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> "DISABLED"
            null -> null
        },
        subtitleLanguage = when (subtitle) {
            is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> subtitle.track.language
            is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> subtitle.language
            else -> null
        },
        subtitleName = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Internal)?.track?.name,
        subtitleTrackId = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Internal)?.track?.trackId,
        subtitleIsForced = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Internal)?.track?.isForcedHint,
        addonSubtitleId = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Addon)?.id,
        addonSubtitleUrl = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Addon)?.url,
        addonSubtitleAddonName = (subtitle as? PlayerRuntimeController.RememberedSubtitleSelection.Addon)?.addonName,
        audioLanguage = audio?.language,
        audioName = audio?.name,
        audioTrackId = audio?.trackId
    )
    scope.launch { trackPreferenceDataStore.save(id, persisted) }
    // Subtitle delay is keyed per-videoId (not per-contentId) because a delay
    // calibrated against one episode release rarely applies to the next
    // episode. Scoping it to the exact video prevents cross-episode leakage.
    // See issue #1063 discussion.
    val vid = currentVideoId?.takeIf { it.isNotBlank() }
    if (vid != null) {
        val currentDelayMs = _uiState.value.subtitleDelayMs
        scope.launch {
            trackPreferenceDataStore.saveSubtitleDelayMs(vid, currentDelayMs.takeIf { it != 0 })
        }
    }
}

internal fun PlayerRuntimeController.captureCurrentAudioSelectionForSubtitleRefresh(
    player: Player
): PlayerRuntimeController.PendingAudioSelection? {
    val state = _uiState.value
    state.audioTracks.getOrNull(state.selectedAudioTrackIndex)?.let { selected ->
        return PlayerRuntimeController.PendingAudioSelection(
            language = selected.language,
            name = selected.name,
            streamUrl = currentStreamUrl
        )
    }

    player.currentTracks.groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_AUDIO) return@forEach
        for (i in 0 until trackGroup.length) {
            if (trackGroup.isTrackSelected(i)) {
                val format = trackGroup.getTrackFormat(i)
                return PlayerRuntimeController.PendingAudioSelection(
                    language = format.language,
                    name = format.label ?: format.language,
                    streamUrl = currentStreamUrl
                )
            }
        }
    }
    return null
}
