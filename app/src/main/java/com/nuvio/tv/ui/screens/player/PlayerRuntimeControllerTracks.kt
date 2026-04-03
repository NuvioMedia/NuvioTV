package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import com.nuvio.tv.core.player.FrameRateUtils
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import com.nuvio.tv.ui.util.languageCodeToName

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
                        }
                        // Extract video codec, resolution, and bitrate for stream info
                        currentVideoCodec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType)
                            ?: CustomDefaultTrackNameProvider.formatNameFromMime(format.codecs)
                        currentVideoWidth = format.width.takeIf { it > 0 }
                        currentVideoHeight = format.height.takeIf { it > 0 }
                        currentVideoBitrate = format.bitrate.takeIf { it > 0 }
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
                            trackId = format.id,
                            codec = codecName,
                            channelCount = format.channelCount.takeIf { it > 0 },
                            isSelected = isSelected,
                            sampleRate = format.sampleRate.takeIf { it > 0 }
                        )
                    )
                }
            }
            C.TRACK_TYPE_TEXT -> {
                for (i in 0 until trackGroup.length) {
                    val format = trackGroup.getTrackFormat(i)
                    // Skip addon subtitle tracks — they are managed separately
                    if (format.id?.contains(PlayerRuntimeController.ADDON_SUBTITLE_TRACK_ID_PREFIX) == true) continue
                    val isSelected = trackGroup.isTrackSelected(i)
                    if (isSelected) selectedSubtitleIndex = subtitleTracks.size
                    
                    val hasForcedFlag = (format.selectionFlags and C.SELECTION_FLAG_FORCED) != 0
                    val trackTexts = listOfNotNull(format.label, format.language, format.id)
                    val nameHintForced = trackTexts.any { it.contains("forced", ignoreCase = true) }
                    val isSongsAndSigns = trackTexts.any {
                        it.contains("songs", ignoreCase = true) && it.contains("sign", ignoreCase = true)
                    }

                    subtitleTracks.add(
                        TrackInfo(
                            index = subtitleTracks.size,
                            name = format.label ?: format.language ?: "Subtitle ${subtitleTracks.size + 1}",
                            language = format.language,
                            trackId = format.id,
                            codec = CustomDefaultTrackNameProvider.formatNameFromMime(format.sampleMimeType),
                            isForced = hasForcedFlag || nameHintForced || isSongsAndSigns,
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
    if (!pendingAddonTrackId.isNullOrBlank()) {
        if (applyAddonSubtitleOverride(pendingAddonTrackId)) {
            Log.d(PlayerRuntimeController.TAG, "Selecting pending addon subtitle track id=$pendingAddonTrackId")
            pendingAddonSubtitleTrackId = null
            pendingAddonSubtitleLanguage = null
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
    applyPersistedTrackPreference(
        audioTracks = audioTracks,
        subtitleTracks = subtitleTracks
    )
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    maybeAdjustLibassPipelineForTracks(tracks)
}

internal fun PlayerRuntimeController.maybeAdjustLibassPipelineForTracks(tracks: Tracks) {
    if (libassPipelineSwitchInFlight) return

    val hasAssSsaTrack = tracks.hasAssSsaTextTrack()
    if (hasAssSsaTrack) {
        hasDetectedAssSsaTrackForCurrentStream = true
    }
    // Keep libass sticky only after we have actually detected ASS/SSA for this stream.
    // This avoids startup ping-pong but does not keep libass on for streams that never
    // expose ASS/SSA tracks.
    val desiredUseLibass = requestedUseLibassByUser && hasDetectedAssSsaTrackForCurrentStream
    if (desiredUseLibass == activePlayerUsesLibass) return

    val player = _exoPlayer ?: return
    val resumePosition = player.currentPosition.takeIf { it > 0L }
    libassPipelineOverrideForCurrentStream = desiredUseLibass
    libassPipelineSwitchInFlight = true

    _uiState.update { state ->
        state.copy(
            pendingSeekPosition = resumePosition ?: state.pendingSeekPosition,
            showLoadingOverlay = state.loadingOverlayEnabled
        )
    }

    scope.launch {
        releasePlayer()
        initializePlayer(currentStreamUrl, currentHeaders)
    }
}

private fun Tracks.hasAssSsaTextTrack(): Boolean {
    groups.forEach { trackGroup ->
        if (trackGroup.type != C.TRACK_TYPE_TEXT) return@forEach
        for (index in 0 until trackGroup.length) {
            val format = trackGroup.getTrackFormat(index)
            if (format.sampleMimeType == MimeTypes.TEXT_SSA) return true

            val hasAssCodec = format.codecs
                ?.split(',')
                ?.asSequence()
                ?.map { it.trim().lowercase(Locale.US) }
                ?.any { codec ->
                    codec == MimeTypes.TEXT_SSA ||
                        codec == "s_text/ass" ||
                        codec == "s_text/ssa" ||
                        codec.endsWith("/x-ssa")
                } == true
            if (hasAssCodec) return true
        }
    }
    return false
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

internal fun PlayerRuntimeController.findMatchingTrackIndex(
    tracks: List<TrackInfo>,
    target: PlayerRuntimeController.RememberedTrackSelection
): Int {
    val targetTrackId = normalizeTrackMatchValue(target.trackId)
    val targetName = normalizeTrackMatchValue(target.name)
    val targetLang = normalizeTrackMatchValue(target.language)

    val exactTrackIdIndex = if (!targetTrackId.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.trackId) == targetTrackId &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang) &&
                (targetName.isNullOrBlank() || normalizeTrackMatchValue(track.name) == targetName ||
                    normalizeTrackMatchValue(track.name)?.contains(targetName) == true)
        }
    } else {
        -1
    }
    if (exactTrackIdIndex >= 0) return exactTrackIdIndex

    val exactNameIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name) == targetName &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (exactNameIndex >= 0) return exactNameIndex

    val nameContainsIndex = if (!targetName.isNullOrBlank()) {
        tracks.indexOfFirst { track ->
            normalizeTrackMatchValue(track.name)?.contains(targetName) == true &&
                (targetLang.isNullOrBlank() || normalizeTrackMatchValue(track.language) == targetLang)
        }
    } else {
        -1
    }
    if (nameContainsIndex >= 0) return nameContainsIndex

    return if (!targetLang.isNullOrBlank()) {
        // Detect the regional variant from the remembered selection's name/language
        val targetVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = target.language,
            name = target.name,
            trackId = target.trackId
        )
        val langCandidates = tracks.indices.filter { index ->
            val trackLang = normalizeTrackMatchValue(tracks[index].language)
            !trackLang.isNullOrBlank() &&
                (trackLang == targetLang ||
                    trackLang.startsWith("$targetLang-") ||
                    trackLang.startsWith("${targetLang}_"))
        }
        if (langCandidates.size <= 1) {
            langCandidates.firstOrNull() ?: -1
        } else {
            // Multiple tracks with the same base language — prefer the one
            // whose detected variant matches the remembered selection.
            langCandidates.firstOrNull { index ->
                val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = tracks[index].language,
                    name = tracks[index].name,
                    trackId = tracks[index].trackId
                )
                trackVariant == targetVariant
            } ?: langCandidates.first()
        }
    } else {
        -1
    }
}

internal fun PlayerRuntimeController.applyPersistedTrackPreference(
    audioTracks: List<TrackInfo>,
    subtitleTracks: List<TrackInfo>
) {
    val pending = persistedTrackPreference ?: return
    var updatedPending = pending
    var updatedSubtitleIndex: Int? = null
    var updatedAddonSubtitle: com.nuvio.tv.domain.model.Subtitle? = null

    pending.audio?.let { audioSelection ->
        if (audioTracks.isEmpty()) {
            Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio deferred (no tracks yet)")
        } else {
            val index = findMatchingTrackIndex(audioTracks, audioSelection)
            if (index >= 0) {
                val alreadySelected = audioTracks.getOrNull(index)?.isSelected == true
                if (!alreadySelected) {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index lang=${audioTracks[index].language} name=${audioTracks[index].name}")
                    selectAudioTrack(index)
                    _uiState.update { it.copy(selectedAudioTrackIndex = index) }
                } else {
                    Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio index=$index already selected, clearing")
                    updatedPending = updatedPending.copy(audio = null)
                }
            } else {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: audio no match for lang=${audioSelection.language} name=${audioSelection.name}, clearing")
                updatedPending = updatedPending.copy(audio = null)
            }
        }
    }

    when (val subtitleSelection = pending.subtitle) {
        null -> Unit
        PlayerRuntimeController.RememberedSubtitleSelection.Disabled -> {
            val alreadyDisabled = subtitleTracks.none { it.isSelected }
            if (!alreadyDisabled) {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle disabled (re-applying)")
                autoSubtitleSelected = true
                subtitleDisabledByPersistedPreference = true
                disableSubtitles()
                updatedSubtitleIndex = -1
            } else {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: subtitle already disabled, clearing")
                autoSubtitleSelected = true
                subtitleDisabledByPersistedPreference = true
                updatedSubtitleIndex = -1
                updatedPending = updatedPending.copy(subtitle = null)
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Internal -> {
            if (subtitleTracks.isEmpty()) {
                Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle deferred (no tracks yet)")
            } else {
                val index = findMatchingTrackIndex(subtitleTracks, subtitleSelection.track)
                if (index >= 0) {
                    val alreadySelected = subtitleTracks.getOrNull(index)?.isSelected == true
                    if (!alreadySelected) {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index (re-applying)")
                        autoSubtitleSelected = true
                        selectSubtitleTrack(index)
                        updatedSubtitleIndex = index
                    } else {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle index=$index already selected, keeping for pipeline restart")
                        autoSubtitleSelected = true
                        updatedSubtitleIndex = index
                    }
                } else {
                    // No internal track matches — try addon fallback with the same language variant.
                    val resolvedVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                        language = subtitleSelection.track.language,
                        name = subtitleSelection.track.name,
                        trackId = subtitleSelection.track.trackId
                    )
                    val state = _uiState.value
                    val addonFallback = state.addonSubtitles.firstOrNull { subtitle ->
                        PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, resolvedVariant)
                    }
                    if (addonFallback != null) {
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "TRACK_PREF restore: internal no match, falling back to addon lang=${addonFallback.lang} variant=$resolvedVariant"
                        )
                        autoSubtitleSelected = true
                        subtitleAddonRestoredByPersistedPreference = true
                        pendingRestoredAddonSubtitle = addonFallback
                        selectAddonSubtitle(addonFallback)
                        updatedAddonSubtitle = addonFallback
                        updatedPending = updatedPending.copy(subtitle = null)
                    } else {
                        Log.d(PlayerRuntimeController.TAG, "TRACK_PREF restore: internal subtitle no match, no addon fallback for variant=$resolvedVariant, clearing")
                        updatedPending = updatedPending.copy(subtitle = null)
                    }
                }
            }
        }
        is PlayerRuntimeController.RememberedSubtitleSelection.Addon -> {
            val state = _uiState.value
            val addonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                subtitle.addonName == subtitleSelection.addonName && subtitle.id == subtitleSelection.id
            } ?: state.addonSubtitles.firstOrNull { subtitle ->
                subtitle.addonName == subtitleSelection.addonName &&
                    PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, subtitleSelection.language)
            } ?: state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, subtitleSelection.language)
            }
            if (addonMatch != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "Restoring same-series addon subtitle lang=${addonMatch.lang} id=${addonMatch.id}"
                )
                autoSubtitleSelected = true
                subtitleAddonRestoredByPersistedPreference = true
                pendingRestoredAddonSubtitle = addonMatch
                selectAddonSubtitle(addonMatch)
                updatedAddonSubtitle = addonMatch
                updatedPending = updatedPending.copy(subtitle = null)
            }
        }
    }

    _uiState.update { state ->
        state.copy(
            selectedSubtitleTrackIndex = updatedSubtitleIndex ?: state.selectedSubtitleTrackIndex,
            selectedAddonSubtitle = updatedAddonSubtitle ?: if (updatedSubtitleIndex != null) null else state.selectedAddonSubtitle
        )
    }
    persistedTrackPreference =
        updatedPending.takeUnless { it.audio == null && it.subtitle == null }
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
        if (target == SUBTITLE_LANGUAGE_FORCED) {
            val forcedIndex = findBestForcedSubtitleTrackIndex(subtitleTracks)
            if (forcedIndex >= 0) return forcedIndex
            if (targetPosition == 0) return -1
            continue
        }
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
                if (targetPosition == 0) {
                    return -1
                }
            }
            if (normalizedTarget == "es-419") {
                val latinoFromGenericEs = findLatinoSpanishInGenericEsTracks(subtitleTracks)
                if (latinoFromGenericEs >= 0) {
                    Log.d(
                        PlayerRuntimeController.TAG,
                        "AUTO_SUB pick internal es-419 via generic-es tags index=$latinoFromGenericEs"
                    )
                    return latinoFromGenericEs
                }
                if (targetPosition == 0) {
                    return -1
                }
            }
            continue
        }
        if (candidateIndexes.size == 1) {
            // For regional targets, verify the single candidate is actually the right variant.
            // A track with language="por" matches both "pt" and "pt-br" by language code,
            // but may be the wrong accent based on its name tags.
            if (normalizedTarget == "pt" || normalizedTarget == "es") {
                val track = subtitleTracks[candidateIndexes.first()]
                val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
                    language = track.language,
                    name = track.name,
                    trackId = track.trackId
                )
                if (variant != normalizedTarget && variant != track.language?.lowercase()) {
                    // Single candidate is a different variant (e.g. PT-BR when we want PT).
                    // Skip it so the search can continue to secondary target or addon fallback.
                    continue
                }
            }
            return candidateIndexes.first()
        }

        if (normalizedTarget == "pt" || normalizedTarget == "pt-br") {
            val tieBroken = breakPortugueseSubtitleTie(
                subtitleTracks = subtitleTracks,
                candidateIndexes = candidateIndexes,
                normalizedTarget = normalizedTarget
            )
            if (tieBroken >= 0) return tieBroken
        }
        if (normalizedTarget == "es" || normalizedTarget == "es-419") {
            val tieBroken = breakSpanishSubtitleTie(
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

private fun findBestForcedSubtitleTrackIndex(subtitleTracks: List<TrackInfo>): Int {
    // isForced is set from both the ExoPlayer SELECTION_FLAG_FORCED and name/label/id containing "forced"
    return subtitleTracks.indexOfFirst { it.isForced }
}

internal fun PlayerRuntimeController.findBrazilianPortugueseInGenericPtTracks(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericPtIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "pt"
    }
    if (genericPtIndexes.isEmpty()) return -1

    val brazilianNonForced = genericPtIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
            subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    }
    if (brazilianNonForced.isNotEmpty()) return brazilianNonForced.first()

    return genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
    } ?: genericPtIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakPortugueseSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasBrazilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.BRAZILIAN_TAGS)
    }

    fun hasEuropeanTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.EUROPEAN_PT_TAGS)
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

internal fun PlayerRuntimeController.findLatinoSpanishInGenericEsTracks(
    subtitleTracks: List<TrackInfo>
): Int {
    val genericEsIndexes = subtitleTracks.indices.filter { index ->
        val trackLanguage = subtitleTracks[index].language ?: return@filter false
        PlayerSubtitleUtils.normalizeLanguageCode(trackLanguage) == "es"
    }
    if (genericEsIndexes.isEmpty()) return -1

    val latinoNonForced = genericEsIndexes.filter { index ->
        !subtitleTracks[index].isForced &&
            subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }
    if (latinoNonForced.isNotEmpty()) return latinoNonForced.first()

    return genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS) &&
            !subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    } ?: genericEsIndexes.firstOrNull { index ->
        subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    } ?: -1
}

internal fun PlayerRuntimeController.breakSpanishSubtitleTie(
    subtitleTracks: List<TrackInfo>,
    candidateIndexes: List<Int>,
    normalizedTarget: String
): Int {
    fun hasLatinoTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.LATINO_TAGS)
    }

    fun hasCastilianTags(index: Int): Boolean {
        return subtitleHasAnyTag(subtitleTracks[index], PlayerSubtitleUtils.CASTILIAN_TAGS)
    }

    return if (normalizedTarget == "es-419") {
        candidateIndexes.firstOrNull { hasLatinoTags(it) && !hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { hasLatinoTags(it) }
            ?: candidateIndexes.first()
    } else {
        candidateIndexes.firstOrNull { hasCastilianTags(it) && !hasLatinoTags(it) }
            ?: candidateIndexes.firstOrNull { hasCastilianTags(it) }
            ?: candidateIndexes.firstOrNull { !hasLatinoTags(it) }
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
    if (internalIndex >= 0 && hasScannedTextTracksOnce) {
        // Determine which target position this internal match satisfies,
        // taking regional variant into account so that e.g. a PT-BR track
        // is not treated as a primary match when the user wants PT.
        val matchedTrack = state.subtitleTracks[internalIndex]
        val trackVariant = PlayerSubtitleUtils.detectTrackLanguageVariant(
            language = matchedTrack.language,
            name = matchedTrack.name,
            trackId = matchedTrack.trackId
        )
        val matchedTargetPosition = targets.indexOfFirst { target ->
            val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(target)
            trackVariant == normalizedTarget ||
                PlayerSubtitleUtils.matchesLanguageCode(trackVariant, target)
        }
        // If the match is only for a secondary (non-primary) target and addon subtitles haven't
        // loaded yet, defer - a primary addon subtitle may still arrive.
        val addonSubtitlesLoaded = !state.isLoadingAddonSubtitles
        if (matchedTargetPosition > 0 && !addonSubtitlesLoaded) {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SUB defer: internal match is secondary target pos=$matchedTargetPosition, addons still loading"
            )
            return
        }
        // If internal match is secondary and a primary addon match exists, prefer the addon.
        if (matchedTargetPosition > 0 && addonSubtitlesLoaded) {
            val primaryTarget = targets.first()
            val primaryAddonMatch = state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, primaryTarget)
            }
            if (primaryAddonMatch != null) {
                autoSubtitleSelected = true
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB pick addon (primary) over internal (secondary): addon lang=${primaryAddonMatch.lang} vs internal variant=$trackVariant"
                )
                selectAddonSubtitle(primaryAddonMatch)
                return
            }
        }
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

    if (targets.contains(SUBTITLE_LANGUAGE_FORCED)) {
        if (hasScannedTextTracksOnce) {
            autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: forced subtitles requested but no forced internal track found")
            return
        }
        Log.d(PlayerRuntimeController.TAG, "AUTO_SUB defer forced: text tracks not scanned yet")
        return
    }

    val selectedAddonMatchesTarget = state.selectedAddonSubtitle != null &&
        targets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(state.selectedAddonSubtitle.lang, target) }
    if (selectedAddonMatchesTarget) {
        val selectedMatchesPrimary = PlayerSubtitleUtils.matchesLanguageCode(
            state.selectedAddonSubtitle!!.lang, targets.first()
        )
        if (selectedMatchesPrimary) {
            autoSubtitleSelected = true
            Log.d(PlayerRuntimeController.TAG, "AUTO_SUB stop: matching addon already selected (primary match)")
            return
        }
        Log.d(
            PlayerRuntimeController.TAG,
            "AUTO_SUB: selected addon ${state.selectedAddonSubtitle.lang} matches secondary target, checking for primary addon"
        )
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

    val addonMatch = run {
        // Try each target in priority order so primary language is preferred over secondary.
        for (target in targets) {
            val match = state.addonSubtitles.firstOrNull { subtitle ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
            if (match != null) {
                Log.d(
                    PlayerRuntimeController.TAG,
                    "AUTO_SUB addon fallback: target=$target matched addon lang=${match.lang} id=${match.id} " +
                        "(addons=${state.addonSubtitles.size}, targets=$targets)"
                )
                return@run match
            }
        }
        null
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
    _uiState.update { state ->
        if (!preserveCurrentDetection) {
            state.copy(
                detectedFrameRateRaw = 0f,
                detectedFrameRate = 0f,
                detectedFrameRateSource = null,
                afrProbeRunning = false
            )
        } else {
            state.copy(afrProbeRunning = false)
        }
    }
    if (!frameRateMatchingEnabled) return

    val token = ++frameRateProbeToken
    frameRateProbeJob = scope.launch(Dispatchers.IO) {
        try {
            delay(PlayerRuntimeController.TRACK_FRAME_RATE_GRACE_MS)
            if (!isActive) return@launch
            val stateSnapshot = withContext(Dispatchers.Main) { _uiState.value }
            val trackAlreadySet = stateSnapshot.detectedFrameRateSource == FrameRateSource.TRACK &&
                stateSnapshot.detectedFrameRate > 0f
            if (trackAlreadySet) {
                if (!allowAmbiguousTrackOverride) return@launch

                val trackRaw = if (stateSnapshot.detectedFrameRateRaw > 0f) {
                    stateSnapshot.detectedFrameRateRaw
                } else {
                    stateSnapshot.detectedFrameRate
                }
                if (!PlayerFrameRateHeuristics.isAmbiguousCinema24(trackRaw)) return@launch
            }

            withContext(Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = true) }
                }
            }

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
        } finally {
            withContext(NonCancellable + Dispatchers.Main) {
                if (token == frameRateProbeToken) {
                    _uiState.update { it.copy(afrProbeRunning = false) }
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
            builder.setPreferredTextLanguage(null)
        } else {
            val userDisabledSubtitles = autoSubtitleSelected &&
                _uiState.value.selectedSubtitleTrackIndex == -1 &&
                _uiState.value.selectedAddonSubtitle == null
            if (!userDisabledSubtitles) {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            }
            if (preferred == SUBTITLE_LANGUAGE_FORCED) {
                builder.setPreferredTextLanguage(null)
            } else {
                builder.setPreferredTextLanguage(preferred)
            }
        }

        player.trackSelectionParameters = builder.build()
    }
}
