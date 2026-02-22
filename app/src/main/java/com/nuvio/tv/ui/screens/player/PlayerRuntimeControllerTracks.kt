package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.FrameRateMatchingMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

internal fun PlayerRuntimeController.updateAvailableTracks(tracks: Tracks) {
    val audioTracks = mutableListOf<TrackInfo>()
    val subtitleTracks = mutableListOf<TrackInfo>()
    var selectedAudioIndex = -1
    var selectedSubtitleIndex = -1

    tracks.groups.forEachIndexed { groupIndex, trackGroup ->
        val trackType = trackGroup.type
        
        when (trackType) {
            C.TRACK_TYPE_VIDEO -> {
                
                for (i in 0 until trackGroup.length) {
                    if (trackGroup.isTrackSelected(i)) {
                        val format = trackGroup.getTrackFormat(i)
                        if (format.frameRate > 0f) {
                            val raw = format.frameRate
                            val snapped = FrameRateUtils.snapToStandardRate(raw)
                            val ambiguousCinemaTrack = PlayerFrameRateHeuristics.isAmbiguousCinema24(raw)
                            if (!ambiguousCinemaTrack) {
                                frameRateProbeJob?.cancel()
                            }
                            _uiState.update {
                                it.copy(
                                    detectedFrameRateRaw = raw,
                                    detectedFrameRate = snapped,
                                    detectedFrameRateSource = FrameRateSource.TRACK
                                )
                            }
                            if (ambiguousCinemaTrack &&
                                _uiState.value.frameRateMatchingMode != FrameRateMatchingMode.OFF &&
                                currentStreamUrl.isNotBlank() &&
                                frameRateProbeJob?.isActive != true
                            ) {
                                startFrameRateProbe(
                                    url = currentStreamUrl,
                                    headers = currentHeaders,
                                    frameRateMatchingEnabled = true,
                                    preserveCurrentDetection = true,
                                    allowAmbiguousTrackOverride = true
                                )
                            }
                        }
                        break
                    }
                }
            }
            C.TRACK_TYPE_AUDIO -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedAudioIndex = audioTracks.size

                    
                    val codecName = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                    val channelLayout = CustomDefaultTrackNameProvider.getChannelLayoutName(
                        format.channelCount
                    )
                    val baseName = format.label ?: format.language ?: "Audio ${audioTracks.size + 1}"
                    val suffix = listOfNotNull(codecName, channelLayout).joinToString(" ")
                    val displayName = if (suffix.isNotEmpty()) "$baseName ($suffix)" else baseName

                    audioTracks.add(
                        TrackInfo(
                            index = audioTracks.size,
                            name = displayName,
                            language = format.language,
                            codec = codecName,
                            channelCount = format.channelCount.takeIf { it > 0 },
                            isSelected = isSelected
                        )
                    )
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedSubtitleIndex = subtitleTracks.size
                    
                    subtitleTracks.add(
                        TrackInfo(
                            index = subtitleTracks.size,
                            name = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}",
                            language = format.language,
                            trackId = format.id,
                            isSelected = isSelected
                        )
                    )
                }
            }
        }
    }

    hasScannedTextTracksOnce = true
    Log.d(
        PlayerRuntimeController.TAG,
        "TRACKS updated: internalSubs=${subtitleTracks.size}, selectedInternalIndex=$selectedSubtitleIndex, " +
            "selectedAddon=${_uiState.value.selectedAddonSubtitle?.lang}, " +
            "pendingAddonLang=$pendingAddonSubtitleLanguage, pendingAddonTrackId=$pendingAddonSubtitleTrackId"
    )

    val pendingAddonTrackId = pendingAddonSubtitleTrackId
    if (!pendingAddonTrackId.isNullOrBlank() && subtitleTracks.isNotEmpty()) {
        val addonTrackIndex = subtitleTracks.indexOfFirst { it.trackId == pendingAddonTrackId }
        if (addonTrackIndex >= 0) {
            Log.d(
                PlayerRuntimeController.TAG,
                "Selecting pending addon subtitle track id=$pendingAddonTrackId index=$addonTrackIndex"
            )
            selectSubtitleTrack(addonTrackIndex)
            selectedSubtitleIndex = if (_uiState.value.selectedAddonSubtitle != null) -1 else addonTrackIndex
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
        } else {
            val pendingLang = pendingAddonSubtitleLanguage
            val hasManualAddonSelection = _uiState.value.selectedAddonSubtitle != null
            if (hasManualAddonSelection) {
                if (!pendingLang.isNullOrBlank()) {
                    val addonFallbackIndex = subtitleTracks.indexOfLast {
                        PlayerSubtitleUtils.matchesLanguageCode(it.language, pendingLang)
                    }
                    if (addonFallbackIndex >= 0) {
                        selectSubtitleTrack(addonFallbackIndex)
                        selectedSubtitleIndex = -1
                        pendingAddonSubtitleTrackId = null
                        pendingAddonSubtitleLanguage = null
                    }
                }
            }
        }
    }

    val pendingLang = pendingAddonSubtitleLanguage
    if (
        pendingAddonSubtitleTrackId.isNullOrBlank() &&
        pendingLang != null &&
        subtitleTracks.isNotEmpty() &&
        _uiState.value.selectedAddonSubtitle == null
    ) {
        val preferredIndex = findBestInternalSubtitleTrackIndex(
            subtitleTracks = subtitleTracks,
            targets = listOf(pendingLang)
        )
        if (preferredIndex >= 0) {
            selectSubtitleTrack(preferredIndex)
            selectedSubtitleIndex = preferredIndex
        } else {
            Log.d(
                PlayerRuntimeController.TAG,
                "Skipping pending subtitle track switch: no text track matches language=$pendingLang"
            )
        }
        pendingAddonSubtitleLanguage = null
    }

    maybeApplyRememberedAudioSelection(audioTracks)
    maybeRestorePendingAudioSelectionAfterSubtitleRefresh(audioTracks)?.let { restoredIndex ->
        selectedAudioIndex = restoredIndex
    }

    _uiState.update {
        it.copy(
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            selectedAudioTrackIndex = selectedAudioIndex,
            selectedSubtitleTrackIndex = selectedSubtitleIndex
        )
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
}

internal fun PlayerRuntimeController.maybeApplyRememberedAudioSelection(audioTracks: List<TrackInfo>) {
    if (hasAppliedRememberedAudioSelection) return
    if (!streamReuseLastLinkEnabled) return
    if (audioTracks.isEmpty()) return
    if (rememberedAudioLanguage.isNullOrBlank() && rememberedAudioName.isNullOrBlank()) return

    val targetLang = normalizeTrackMatchValue(rememberedAudioLanguage)
    val targetName = normalizeTrackMatchValue(rememberedAudioName)

    val index = audioTracks.indexOfFirst { track ->
        val trackLang = normalizeTrackMatchValue(track.language)
        val trackName = normalizeTrackMatchValue(track.name)
        val langMatch = !targetLang.isNullOrBlank() &&
            !trackLang.isNullOrBlank() &&
            (trackLang == targetLang || trackLang.startsWith("$targetLang-"))
        val nameMatch = !targetName.isNullOrBlank() &&
            !trackName.isNullOrBlank() &&
            (trackName == targetName || trackName.contains(targetName))
        langMatch || nameMatch
    }
    if (index < 0) {
        hasAppliedRememberedAudioSelection = true
        return
    }

    selectAudioTrack(index)
    hasAppliedRememberedAudioSelection = true
}

internal fun PlayerRuntimeController.normalizeTrackMatchValue(value: String?): String? = value
    ?.lowercase()
    ?.replace(Regex("\\s+"), " ")
    ?.trim()
    ?.takeIf { it.isNotBlank() }

internal fun PlayerRuntimeController.maybeRestorePendingAudioSelectionAfterSubtitleRefresh(
    audioTracks: List<TrackInfo>
): Int? {
    val pending = pendingAudioSelectionAfterSubtitleRefresh ?: return null
    if (pending.streamUrl != currentStreamUrl) {
        pendingAudioSelectionAfterSubtitleRefresh = null
        return null
    }
    if (audioTracks.isEmpty()) return null

    val targetLang = normalizeTrackMatchValue(pending.language)
    val targetName = normalizeTrackMatchValue(pending.name)

    fun languageMatches(trackLanguage: String?): Boolean {
        val trackLang = normalizeTrackMatchValue(trackLanguage)
        return !targetLang.isNullOrBlank() &&
            !trackLang.isNullOrBlank() &&
            (trackLang == targetLang ||
                trackLang.startsWith("$targetLang-") ||
                trackLang.startsWith("${targetLang}_"))
    }

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName
        }
    } else {
        -1
    }

    val nameContainsIndex = if (exactNameIndex < 0 && !targetName.isNullOrBlank()) {
        audioTracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true
        }
    } else {
        -1
    }

    val languageIndex = if (exactNameIndex < 0 && nameContainsIndex < 0) {
        audioTracks.indexOfFirst { track -> languageMatches(track.language) }
    } else {
        -1
    }

    val index = when {
        exactNameIndex >= 0 -> exactNameIndex
        nameContainsIndex >= 0 -> nameContainsIndex
        else -> languageIndex
    }

    pendingAudioSelectionAfterSubtitleRefresh = null
    if (index < 0) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Audio restore skipped after subtitle refresh: no match for lang=$targetLang name=$targetName"
        )
        return null
    }

    val restoredTrack = audioTracks[index]
    Log.d(
        PlayerRuntimeController.TAG,
        "Restoring audio after subtitle refresh index=$index lang=${restoredTrack.language} name=${restoredTrack.name}"
    )
    selectAudioTrack(index)
    return index
}

internal fun PlayerRuntimeController.subtitleLanguageTargets(): List<String> {
    val preferred = _uiState.value.subtitleStyle.preferredLanguage.lowercase()
    if (preferred == "none") return emptyList()
    val secondary = _uiState.value.subtitleStyle.secondaryPreferredLanguage?.lowercase()
    return listOfNotNull(preferred, secondary)
}

internal fun PlayerRuntimeController.findBestInternalSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    targets: List<String>
): Int {
    for ((targetPosition, target) in targets.withIndex()) {
        val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
        val candidateIndexes = subtitleTracks.indices.filter { index ->
            PlayerSubtitleUtils.matchesLanguageCode(subtitleTracks[index].language, target)
        }
        if (candidateIndexes.isEmpty()) {
            if (normalizedTarget == "pt-br") {
                val brazilianFromGenericPt = findBrazilianPortugueseInGenericPtTracks(subtitleTracks)
                if (brazilianFromGenericPt >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal pt-br via generic-pt tags index=$brazilianFromGenericPt"
                    )
                    return brazilianFromGenericPt
                }
                // Specific PT-BR rule:
                // generic "pt" tracks without brazilian tags are not accepted as PT-BR.
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) return candidateIndexes.first()

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        return candidateIndexes.first()
    }
    return -1
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerRuntimeController.PORTUGUESE_EUROPEAN_TAGS)
    }

    return if (normalizedTarget == "pt-br") {
        candidateIndexes.firstOrNull { hasBrazilianTags(it) && !hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasEuropeanTags(it) && !hasBrazilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasEuropeanTags(it) }
            ?: candidateIndexes.firstOrNull { !hasBrazilianTags(it) }
            ?: candidateIndexes.first()
    }
}

internal fun PlayerRuntimeController.subtitleHasAnyTag(track: TrackInfo, tags: List<String>): Boolean {
    val haystack = listOfNotNull(track.name, track.language, track.trackId)
        .joinToString(" ")
        .lowercase(Locale.ROOT)
    return tags.any { tag -> haystack.contains(tag) }
}

internal fun PlayerRuntimeController.tryAutoSelectPreferredSubtitleFromAvailableTracks() {
    if (autoSubtitleSelected) return

    val state = _uiState.value
    val targets = subtitleLanguageTargets()
    Log.d(
        PlayerRuntimeController.TAG,
        "AUTO_SUB eval: targets=$targets, scannedText=$hasScannedTextTracksOnce, " +
            "internalCount=${state.subtitleTracks.size}, selectedInternal=${state.selectedSubtitleTrackIndex}, " +
            "addonCount=${state.addonSubtitles.size}, selectedAddon=${state.selectedAddonSubtitle?.lang}"
    )
    if (targets.isEmpty()) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred=none")
        return
    }

    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = targets
    )
    if (internalIndex >= 0) {
        autoSubtitleSelected = true
        val currentInternal = state.selectedSubtitleTrackIndex
        val currentAddon = state.selectedAddonSubtitle
        if (currentInternal != internalIndex || currentAddon != null) {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick internal index=$internalIndex lang=${state.subtitleTracks[internalIndex].language}")
            selectSubtitleTrack(internalIndex)
            _uiState.update { it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null) }
        } else {
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: preferred internal already selected")
        }
        return
    }

    val selectedAddonMatchesTarget = state.selectedAddonSubtitle != null &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(state.selectedAddonSubtitle.lang, target) }
    if (selectedAddonMatchesTarget) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (no internal match)")
        return
    }

    // Wait until we have at least one full text-track scan to avoid choosing addon too early.
    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: text tracks not scanned yet")
        return
    }

    val playerReady = _exoPlayer?.playbackState == Player.STATE_READY
    if (!playerReady) {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer addon fallback: player not ready")
        return
    }

    val addonMatch = state.addonSubtitles.firstOrNull { subtitle ->
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) }
    }
    if (addonMatch != null) {
        autoSubtitleSelected = true
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB pick addon lang=${addonMatch.lang} id=${addonMatch.id}")
        selectAddonSubtitle(addonMatch)
    } else {
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB no addon match for targets=$targets")
    }
}

internal fun PlayerRuntimeController.startFrameRateProbe(
    url: String,
    headers: Map<String, String>,
    frameRateMatchingEnabled: Boolean,
    preserveCurrentDetection: Boolean = false,
    allowAmbiguousTrackOverride: Boolean = false
) {
    frameRateProbeJob?.cancel()
    if (!preserveCurrentDetection) {
        _uiState.update {
            it.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null
            )
        }
    }
    if (!frameRateMatchingEnabled) return

    val token = ++frameRateProbeToken
    frameRateProbeJob = scope.launch(Dispatchers.IO) {
        delay(PlayerRuntimeController.TRACK_FRAME_RATE_GRACE_MS)
        if (!isActive) return@launch
        val trackAlreadySet = withContext(Dispatchers.Main) {
            _uiState.value.detectedFrameRateSource == FrameRateSource.TRACK &&
                _uiState.value.detectedFrameRate > 0f
        }
        if (trackAlreadySet && !allowAmbiguousTrackOverride) return@launch

        val detection = FrameRateUtils.detectFrameRateFromSource(context, url, headers)
            ?: return@launch
        if (!isActive) return@launch
        withContext(Dispatchers.Main) {
            if (token == frameRateProbeToken) {
                val state = _uiState.value
                val shouldApplyInitial = state.detectedFrameRate <= 0f
                val shouldOverrideAmbiguousTrack = allowAmbiguousTrackOverride &&
                    PlayerFrameRateHeuristics.shouldProbeOverrideTrack(state, detection)

                if (shouldApplyInitial || shouldOverrideAmbiguousTrack) {
                    _uiState.update {
                        it.copy(
                            detectedFrameRateRaw = detection.raw,
                            detectedFrameRate = detection.snapped,
                            detectedFrameRateSource = FrameRateSource.PROBE
                        )
                    }
                }
            }
        }
    }
}

internal fun PlayerRuntimeController.applySubtitlePreferences(preferred: String, secondary: String?) {
    _exoPlayer?.let { player ->
        val builder = player.trackSelectionParameters.buildUpon()

        if (preferred == "none") {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setPreferredTextLanguage(preferred)
        }

        player.trackSelectionParameters = builder.build()
    }
}
