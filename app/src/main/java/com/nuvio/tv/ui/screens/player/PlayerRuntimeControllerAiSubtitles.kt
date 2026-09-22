package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.data.local.displayName
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleAiModel
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationManager
import com.nuvio.tv.ui.screens.player.subtitles.SubtitleTranslationService
import com.nuvio.tv.ui.screens.player.subtitles.TRANSLATION_ERROR_CONTENT_BLOCKED
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Creates and observes AI subtitle translation settings for ExoPlayer playback.
 *
 * Smart-subtitles ladder (when "smart AI subtitles" is on):
 * 1. Preferred-language embedded track (no AI)
 * 2. AI translation of embedded track (prefer original-language)
 * 3. AI translation of best-scoring pivot-language addon (score ≥ 50)
 * 4. Preferred-language addon with best release-name score
 *
 * Manual AI picks from the menu are user-locked and skip automatic overrides.
 */
internal fun PlayerRuntimeController.ensureSubtitleTranslationManager(): SubtitleTranslationManager {
    subtitleTranslationManager?.let { return it }
    val manager = SubtitleTranslationManager(
        service = SubtitleTranslationService(
            apiKeyProvider = { subtitleAiApiKey },
            modelProvider = { subtitleAiModel }
        ),
        targetLanguage = resolveSubtitleAiTargetLanguageName(),
        scope = scope
    )
    manager.removeHearingImpaired = currentPlayerSettingsForReport.subtitleStyle.stripSdh
    manager.onTranslatingChanged = { translating ->
        _uiState.update { it.copy(isAiSubtitleTranslating = translating) }
    }
    manager.onBatchResult = { success, error ->
        if (!success && error != null && error != TRANSLATION_ERROR_CONTENT_BLOCKED && error != "RATE_LIMITED") {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle batch failed: $error")
        }
    }
    manager.onUntranslatableSource = {
        if (manager.isEnabled) {
            Log.w(PlayerRuntimeController.TAG, "AI subtitle source has no extractable text; trying next source")
            val switched = selectAiTranslationSourceIfAvailable(excludeCurrent = true)
            if (!switched) {
                Log.w(PlayerRuntimeController.TAG, "No usable AI source left; disabling translation")
                setAiSubtitleTranslationEnabled(false)
                if (canRunAiAutoSelectLadder()) {
                    applyAiAutoSelectLadder()
                }
            }
        }
    }
    subtitleTranslationManager = manager
    return manager
}

internal fun PlayerRuntimeController.observeSubtitleAiSettings() {
    scope.launch {
        combine(
            playerSettingsDataStore.playerSettings,
            deviceLocalPlayerPreferences.subtitleAiApiKey
        ) { settings, apiKey ->
            Triple(settings.subtitleStyle, apiKey, settings.internalPlayerEngine)
        }.distinctUntilChanged().collect { (style, apiKey, _) ->
            subtitleAiApiKey = apiKey
            subtitleAiModel = runCatching {
                SubtitleAiModel.valueOf(style.aiModel)
            }.getOrDefault(SubtitleAiModel.GROQ_LLAMA_70B)
            subtitleAiFeatureEnabled = style.aiEnabled
            subtitleAiAutoSelect = style.aiAutoSelect

            val manager = ensureSubtitleTranslationManager()
            manager.updateService(apiKey, subtitleAiModel)
            manager.targetLanguage = resolveSubtitleAiTargetLanguageName()
            manager.removeHearingImpaired = style.stripSdh

            val canUseAi = !isUsingMpvEngine() &&
                style.aiEnabled &&
                apiKey.isNotBlank()

            if (!canUseAi && manager.isEnabled) {
                setAiSubtitleTranslationEnabled(false)
            } else if (style.aiAutoSelect && canUseAi) {
                applySubtitleAutoSelectPolicy()
            }

            _uiState.update {
                it.copy(
                    aiSubtitleAvailable = canUseAi,
                    aiSubtitleTranslationActive = manager.isEnabled && canUseAi,
                    subtitleAiFeatureEnabled = style.aiEnabled
                )
            }
        }
    }
}

/**
 * Single entry for automatic subtitle selection. When smart AI subtitles are enabled, uses
 * preferred-embedded → AI (embedded or scored addon) → preferred scored addon.
 * Otherwise keeps the classic preferred-language auto-select.
 */
internal fun PlayerRuntimeController.applySubtitleAutoSelectPolicy() {
    if (isUserExplicitSubtitleSelection) {
        Log.d(PlayerRuntimeController.TAG, "SUB_POLICY stop: user explicit selection")
        return
    }
    if (aiSubtitleUserLocked && subtitleTranslationManager?.isEnabled == true) {
        selectAiTranslationSourceIfAvailable()
        return
    }
    if (canRunAiAutoSelectLadder()) {
        applyAiAutoSelectLadder()
        return
    }
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    if (subtitleTranslationManager?.isEnabled == true) {
        refreshAiSubtitleSourceAndMaybeUpgrade()
    }
}

internal fun PlayerRuntimeController.canRunAiAutoSelectLadder(): Boolean {
    return subtitleAiAutoSelect &&
        subtitleAiFeatureEnabled &&
        subtitleAiApiKey.isNotBlank() &&
        !isUsingMpvEngine()
}

/**
 * Priority: preferred embedded → AI on original/embedded → scored pivot addon → preferred scored addon.
 */
internal fun PlayerRuntimeController.applyAiAutoSelectLadder() {
    if (isUserExplicitSubtitleSelection || aiSubtitleUserLocked) return
    if (!canRunAiAutoSelectLadder()) return

    val state = _uiState.value
    if (state.subtitleStyle.useForcedSubtitles) {
        tryAutoSelectPreferredSubtitleFromAvailableTracks()
        return
    }

    val targets = subtitleLanguageTargets()
    val primaryTarget = targets.firstOrNull()
    if (primaryTarget == null) {
        autoSubtitleSelected = true
        setAiSubtitleTranslationEnabled(false)
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.NONE,
                reason = "preferred=none",
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = aiSubtitleUserLocked
            )
        )
        Log.d(PlayerRuntimeController.TAG, "AI ladder stop: preferred=none")
        return
    }

    if (!hasScannedTextTracksOnce) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder defer: text tracks not scanned yet")
        return
    }

    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val preferredInternalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = listOf(primaryTarget),
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = selectedAudioTrack
    )
    if (preferredInternalIndex >= 0) {
        val track = state.subtitleTracks[preferredInternalIndex]
        if (trackMatchesPreferredLanguage(track, primaryTarget)) {
            Log.i(
                PlayerRuntimeController.TAG,
                "AI ladder: preferred embedded index=$preferredInternalIndex lang=${track.language}"
            )
            selectSubtitleTrack(preferredInternalIndex)
            _uiState.update {
                it.copy(
                    selectedSubtitleTrackIndex = preferredInternalIndex,
                    selectedAddonSubtitle = null
                )
            }
            autoSubtitleSelected = true
            setAiSubtitleTranslationEnabled(false)
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.PREFERRED_EMBEDDED,
                    reason = "preferred-language embedded available",
                    sourceKind = AiSubtitleSourceKind.EMBEDDED,
                    sourceLabel = track.name,
                    sourceLanguage = track.language,
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = false
                )
            )
            return
        }
    }

    val aiSourceIndex = findAiSourceSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        originalLanguage = contentLanguage
    )
    if (aiSourceIndex >= 0) {
        val track = state.subtitleTracks[aiSourceIndex]
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: AI translation source index=$aiSourceIndex lang=${track.language}"
        )
        setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
        autoSubtitleSelected = true
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.AI_EMBEDDED,
                reason = if (contentLanguage != null &&
                    PlayerSubtitleUtils.matchesLanguageCode(track.language, contentLanguage)
                ) {
                    "embedded original-language source"
                } else {
                    "embedded text source"
                },
                sourceKind = AiSubtitleSourceKind.EMBEDDED,
                sourceLabel = track.name,
                sourceLanguage = track.language,
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = false
            )
        )
        return
    }

    if (state.isLoadingAddonSubtitles) {
        Log.d(PlayerRuntimeController.TAG, "AI ladder defer: waiting for addon subtitles")
        return
    }

    val scoredAddon = findBestScoredAddonAiSource(state.addonSubtitles)
    if (scoredAddon != null) {
        val (subtitle, score) = scoredAddon
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: scored addon AI source score=$score lang=${subtitle.lang} " +
                "addon=${subtitle.addonName} id=${subtitle.id.take(80)}"
        )
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
        setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
        autoSubtitleSelected = true
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.AI_SCORED_ADDON,
                reason = "pivot addon score ≥ ${SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE}",
                sourceKind = AiSubtitleSourceKind.ADDON,
                sourceLabel = subtitle.addonName,
                sourceLanguage = subtitle.lang,
                matchScore = score,
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = false
            )
        )
        return
    }

    val preferredScored = findBestScoredPreferredLanguageAddon(state.addonSubtitles, primaryTarget)
    if (preferredScored != null) {
        val (subtitle, score) = preferredScored
        Log.i(
            PlayerRuntimeController.TAG,
            "AI ladder: preferred-language addon score=$score lang=${subtitle.lang} " +
                "addon=${subtitle.addonName}"
        )
        setAiSubtitleTranslationEnabled(false)
        selectAddonSubtitle(subtitle)
        _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
        autoSubtitleSelected = true
        publishAiSubtitleDiagnostics(
            AiSubtitleDiagnostics(
                rung = AiSubtitleLadderRung.PREFERRED_SCORED_ADDON,
                reason = "best preferred-language addon by release score",
                sourceKind = AiSubtitleSourceKind.ADDON,
                sourceLabel = subtitle.addonName,
                sourceLanguage = subtitle.lang,
                matchScore = score,
                targetLanguage = resolveSubtitleAiTargetLanguageName(),
                model = subtitleAiModel.name,
                userLocked = false
            )
        )
        return
    }

    Log.i(PlayerRuntimeController.TAG, "AI ladder: no scored preferred addon — classic fallback")
    setAiSubtitleTranslationEnabled(false)
    autoSubtitleSelected = false
    tryAutoSelectPreferredSubtitleFromAvailableTracks()
    publishAiSubtitleDiagnostics(
        AiSubtitleDiagnostics(
            rung = AiSubtitleLadderRung.CLASSIC_FALLBACK,
            reason = "classic preferred-language auto-select",
            targetLanguage = resolveSubtitleAiTargetLanguageName(),
            model = subtitleAiModel.name,
            userLocked = false
        )
    )
}

internal fun PlayerRuntimeController.setAiSubtitleTranslationEnabled(
    enabled: Boolean,
    allowPreferredUpgrade: Boolean = !aiSubtitleUserLocked
) {
    if (enabled && isUsingMpvEngine()) {
        Log.i(PlayerRuntimeController.TAG, "AI subtitle translation ignored on MPV")
        return
    }
    val manager = ensureSubtitleTranslationManager()
    val apiKeyOk = subtitleAiApiKey.isNotBlank()
    val featureOk = subtitleAiFeatureEnabled
    val effective = enabled && apiKeyOk && featureOk && !isUsingMpvEngine()
    if (!effective) {
        aiSubtitleUserLocked = false
    } else if (allowPreferredUpgrade) {
        aiSubtitleUserLocked = false
    }
    if (manager.isEnabled == effective) {
        if (effective) {
            refreshAiSubtitleSourceAndMaybeUpgrade(allowPreferredUpgrade = allowPreferredUpgrade)
        }
        _uiState.update {
            it.copy(
                aiSubtitleTranslationActive = effective,
                aiSubtitleAvailable = !isUsingMpvEngine() && featureOk && apiKeyOk
            )
        }
        return
    }
    if (effective) {
        selectAiTranslationSourceIfAvailable()
    }
    manager.isEnabled = effective
    if (!effective) {
        manager.reset()
        _uiState.update { it.copy(isAiSubtitleTranslating = false) }
    }
    _uiState.update {
        it.copy(
            aiSubtitleTranslationActive = effective,
            aiSubtitleAvailable = !isUsingMpvEngine() && featureOk && apiKeyOk
        )
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "AI subtitle translation enabled=$effective locked=$aiSubtitleUserLocked upgrade=$allowPreferredUpgrade"
    )
    if (effective && allowPreferredUpgrade && !aiSubtitleUserLocked) {
        tryUpgradeAiToPreferredEmbeddedSubtitle()
    }
}

/**
 * Manual "translate this subtitle with AI" from the overlay long-press menu.
 */
internal fun PlayerRuntimeController.translateSubtitleWithAi(
    internalTrackIndex: Int?,
    addonSubtitle: Subtitle?
) {
    if (isUsingMpvEngine()) return
    if (!subtitleAiFeatureEnabled || subtitleAiApiKey.isBlank()) {
        Log.w(PlayerRuntimeController.TAG, "Translate with AI ignored: feature/key unavailable")
        return
    }
    when {
        internalTrackIndex != null && internalTrackIndex >= 0 -> {
            val track = _uiState.value.subtitleTracks.getOrNull(internalTrackIndex) ?: return
            selectSubtitleTrack(internalTrackIndex)
            _uiState.update {
                it.copy(selectedSubtitleTrackIndex = internalTrackIndex, selectedAddonSubtitle = null)
            }
            aiSubtitleUserLocked = true
            setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.MANUAL,
                    reason = "user chose translate with AI",
                    sourceKind = AiSubtitleSourceKind.EMBEDDED,
                    sourceLabel = track.name,
                    sourceLanguage = track.language,
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = true
                )
            )
        }
        addonSubtitle != null -> {
            selectAddonSubtitle(addonSubtitle)
            _uiState.update {
                it.copy(selectedAddonSubtitle = addonSubtitle, selectedSubtitleTrackIndex = -1)
            }
            aiSubtitleUserLocked = true
            setAiSubtitleTranslationEnabled(true, allowPreferredUpgrade = false)
            val score = scoreAddonSubtitleCached(addonSubtitle)
            publishAiSubtitleDiagnostics(
                AiSubtitleDiagnostics(
                    rung = AiSubtitleLadderRung.MANUAL,
                    reason = "user chose translate with AI",
                    sourceKind = AiSubtitleSourceKind.ADDON,
                    sourceLabel = addonSubtitle.addonName,
                    sourceLanguage = addonSubtitle.lang,
                    matchScore = score.takeIf { it > 0 },
                    targetLanguage = resolveSubtitleAiTargetLanguageName(),
                    model = subtitleAiModel.name,
                    userLocked = true
                )
            )
        }
        else -> Log.w(PlayerRuntimeController.TAG, "Translate with AI: no source provided")
    }
}

internal fun PlayerRuntimeController.refreshAiSubtitleSourceAndMaybeUpgrade(
    allowPreferredUpgrade: Boolean = !aiSubtitleUserLocked
) {
    val manager = subtitleTranslationManager ?: return
    if (!manager.isEnabled) return
    if (isUsingMpvEngine()) return
    if (allowPreferredUpgrade && !aiSubtitleUserLocked && !isUserExplicitSubtitleSelection) {
        tryUpgradeAiToPreferredEmbeddedSubtitle()
    }
    if (manager.isEnabled) {
        selectAiTranslationSourceIfAvailable()
    }
}

internal fun PlayerRuntimeController.tryUpgradeAiToPreferredEmbeddedSubtitle(): Boolean {
    val manager = subtitleTranslationManager ?: return false
    if (!manager.isEnabled) return false
    if (aiSubtitleUserLocked) {
        Log.d(PlayerRuntimeController.TAG, "AI upgrade skipped: user locked AI translation")
        return false
    }
    if (isUserExplicitSubtitleSelection) return false
    if (isUsingMpvEngine()) return false

    val state = _uiState.value
    val primaryTarget = subtitleLanguageTargets().firstOrNull() ?: return false
    if (state.subtitleStyle.useForcedSubtitles) return false
    if (!hasScannedTextTracksOnce) return false

    val selectedAudioTrack = selectedAudioTrackForSubtitleMatching(state)
    val internalIndex = findBestInternalSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        targets = listOf(primaryTarget),
        forcedOnly = false,
        normalOnly = true,
        selectedAudioTrack = selectedAudioTrack
    )
    if (internalIndex < 0) return false
    val track = state.subtitleTracks[internalIndex]
    if (!trackMatchesPreferredLanguage(track, primaryTarget)) return false

    Log.i(
        PlayerRuntimeController.TAG,
        "AI upgrade: preferred embedded index=$internalIndex lang=${track.language}"
    )
    selectSubtitleTrack(internalIndex)
    _uiState.update {
        it.copy(selectedSubtitleTrackIndex = internalIndex, selectedAddonSubtitle = null)
    }
    autoSubtitleSelected = true
    setAiSubtitleTranslationEnabled(false)
    return true
}

internal fun PlayerRuntimeController.selectAiTranslationSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (selectEmbeddedAiSourceIfAvailable(excludeCurrent = excludeCurrent)) return true
    if (isUsingMpvEngine()) return false

    val state = _uiState.value
    val excludeId = if (excludeCurrent) state.selectedAddonSubtitle?.id else null
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()

    val current = state.selectedAddonSubtitle
    if (current != null &&
        current.id != excludeId &&
        !current.isStreamProvided &&
        streamSrc.isNotBlank()
    ) {
        val score = scoreAddonSubtitleCached(current)
        if (score >= SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE &&
            isUsableAddonAiSourceLanguage(current)
        ) {
            Log.i(
                PlayerRuntimeController.TAG,
                "AI source: keeping addon score=$score lang=${current.lang} id=${current.id.take(80)}"
            )
            return true
        }
    }

    val best = findBestScoredAddonAiSource(
        addonSubtitles = state.addonSubtitles,
        excludeId = excludeId
    )
    if (best == null) {
        Log.i(PlayerRuntimeController.TAG, "AI source: no usable embedded or scored addon")
        return false
    }
    val (subtitle, score) = best
    Log.i(
        PlayerRuntimeController.TAG,
        "AI source: selecting addon score=$score lang=${subtitle.lang} " +
            "addon=${subtitle.addonName} id=${subtitle.id.take(80)}"
    )
    selectAddonSubtitle(subtitle)
    _uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
    return true
}

internal fun PlayerRuntimeController.resolveStreamReleaseNameForSubtitleScore(): String {
    return listOfNotNull(streamName, contentName)
        .map { it.trim() }
        .firstOrNull { it.isNotBlank() }
        .orEmpty()
}

internal fun scoreAddonSubtitle(streamSource: String, subtitle: Subtitle): Int {
    if (streamSource.isBlank()) return 0
    val key = SubtitleReleaseScoring.subtitleScoreKey(subtitle.id, subtitle.url, subtitle.addonName)
    return SubtitleReleaseScoring.score(streamSource, key)
}

/** Cached score lookup; invalidates when the stream release name changes. */
internal fun PlayerRuntimeController.scoreAddonSubtitleCached(subtitle: Subtitle): Int {
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()
    if (streamSrc.isBlank() || subtitle.isStreamProvided) return 0
    if (subtitleScoreCacheStreamName != streamSrc) {
        subtitleScoreCacheStreamName = streamSrc
        subtitleScoreCache.clear()
    }
    return subtitleScoreCache.getOrPut(subtitle.id) {
        scoreAddonSubtitle(streamSrc, subtitle)
    }
}

/**
 * Best non-preferred-language addon whose release-name score meets the AI source threshold.
 * Prefer original-language, then English, when scores tie.
 */
internal fun PlayerRuntimeController.findBestScoredAddonAiSource(
    addonSubtitles: List<Subtitle>,
    excludeId: String? = null
): Pair<Subtitle, Int>? {
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()
    if (streamSrc.isBlank()) return null
    val original = contentLanguage

    return addonSubtitles
        .asSequence()
        .filter { !it.isStreamProvided }
        .filter { excludeId == null || it.id != excludeId }
        .filter { isUsableAddonAiSourceLanguage(it) }
        .map { subtitle -> subtitle to scoreAddonSubtitleCached(subtitle) }
        .filter { (_, score) -> score >= SubtitleReleaseScoring.AI_ADDON_SOURCE_MIN_SCORE }
        .maxWithOrNull(
            compareByDescending<Pair<Subtitle, Int>> { it.second }
                .thenByDescending {
                    original != null &&
                        PlayerSubtitleUtils.matchesLanguageCode(it.first.lang, original)
                }
                .thenByDescending { PlayerSubtitleUtils.matchesLanguageCode(it.first.lang, "en") }
        )
}

/** Best addon in the preferred language by release-name score (no threshold). */
internal fun PlayerRuntimeController.findBestScoredPreferredLanguageAddon(
    addonSubtitles: List<Subtitle>,
    preferredLanguage: String
): Pair<Subtitle, Int>? {
    val streamSrc = resolveStreamReleaseNameForSubtitleScore()
    val candidates = addonSubtitles
        .asSequence()
        .filter { !it.isStreamProvided }
        .filter { PlayerSubtitleUtils.matchesLanguageCode(it.lang, preferredLanguage) }
        .map { subtitle ->
            val score = if (streamSrc.isBlank()) 0 else scoreAddonSubtitleCached(subtitle)
            subtitle to score
        }
        .toList()
    if (candidates.isEmpty()) return null
    return candidates.maxWithOrNull(
        compareByDescending<Pair<Subtitle, Int>> { it.second }
            .thenBy { it.first.addonName }
    )
}

internal fun PlayerRuntimeController.isUsableAddonAiSourceLanguage(subtitle: Subtitle): Boolean {
    val preferred = subtitleLanguageTargets().firstOrNull() ?: return true
    return !PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, preferred)
}

internal fun PlayerRuntimeController.selectEmbeddedAiSourceIfAvailable(
    excludeCurrent: Boolean = false
): Boolean {
    if (isUsingMpvEngine()) return false
    val state = _uiState.value
    val excludeIndex = if (excludeCurrent) state.selectedSubtitleTrackIndex else -1
    val sourceIndex = findAiSourceSubtitleTrackIndex(
        subtitleTracks = state.subtitleTracks,
        excludeIndex = excludeIndex,
        originalLanguage = contentLanguage
    )
    if (sourceIndex < 0) {
        Log.i(PlayerRuntimeController.TAG, "AI source: no usable embedded text track")
        return false
    }
    val alreadySelected =
        state.selectedAddonSubtitle == null && state.selectedSubtitleTrackIndex == sourceIndex
    if (!alreadySelected) {
        val track = state.subtitleTracks[sourceIndex]
        Log.i(
            PlayerRuntimeController.TAG,
            "AI source: selecting embedded index=$sourceIndex lang=${track.language} name=${track.name}"
        )
        selectSubtitleTrack(sourceIndex)
        _uiState.update {
            it.copy(selectedSubtitleTrackIndex = sourceIndex, selectedAddonSubtitle = null)
        }
    }
    return true
}

/**
 * Any usable embedded text track for AI.
 * Prefers original language, then plain over SDH/CC, labeled over unlabeled.
 * Skips forced / songs-and-signs and bitmap codecs (PGS/DVB/VOBSUB).
 */
internal fun findAiSourceSubtitleTrackIndex(
    subtitleTracks: List<TrackInfo>,
    excludeIndex: Int = -1,
    originalLanguage: String? = null
): Int {
    fun TrackInfo.isEffectivelyForced(): Boolean =
        isForced || name.contains("forced", ignoreCase = true)

    fun TrackInfo.isBitmapCodec(): Boolean {
        val c = codec?.uppercase(Locale.ROOT) ?: return false
        return c == "PGS" || c == "DVB" || c.contains("VOB") || c.contains("PGS")
    }

    fun TrackInfo.isUsableSource(): Boolean = !isEffectivelyForced() && !isBitmapCodec()

    fun bestAmong(predicate: (TrackInfo) -> Boolean): Int {
        val matches = subtitleTracks.withIndex().filter { (index, track) ->
            index != excludeIndex && predicate(track) && track.isUsableSource()
        }
        val plain = matches.firstOrNull { (_, track) ->
            !track.name.contains("SDH", ignoreCase = true) &&
                !track.name.contains("CC", ignoreCase = true)
        }
        return (plain ?: matches.firstOrNull())?.index ?: -1
    }

    if (!originalLanguage.isNullOrBlank()) {
        val original = bestAmong { track ->
            PlayerSubtitleUtils.matchesLanguageCode(track.language, originalLanguage) ||
                trackMatchesPreferredLanguage(track, originalLanguage)
        }
        if (original >= 0) return original
    }

    val labeled = bestAmong { track -> !track.language.isNullOrBlank() }
    if (labeled >= 0) return labeled

    return bestAmong { true }
}

internal fun trackMatchesPreferredLanguage(track: TrackInfo, primaryTarget: String): Boolean {
    val variant = PlayerSubtitleUtils.detectTrackLanguageVariant(
        language = track.language,
        name = track.name,
        trackId = track.trackId
    )
    val normalizedTarget = PlayerSubtitleUtils.normalizeLanguageCode(primaryTarget)
    return PlayerSubtitleUtils.matchesLanguageCode(track.language, primaryTarget) ||
        PlayerSubtitleUtils.matchesLanguageCode(variant, primaryTarget) ||
        variant == normalizedTarget
}

internal fun PlayerRuntimeController.publishAiSubtitleDiagnostics(diagnostics: AiSubtitleDiagnostics) {
    _uiState.update { it.copy(aiSubtitleDiagnostics = diagnostics) }
}

internal fun PlayerRuntimeController.resolveSubtitleAiTargetLanguageName(): String {
    val code = currentPlayerSettingsForReport.subtitleStyle.preferredLanguage
    if (code.isBlank() ||
        code.equals("none", ignoreCase = true) ||
        code.equals(SubtitleLanguageOption.DEVICE, ignoreCase = true)
    ) {
        return Locale.getDefault().displayLanguage.ifBlank { "English" }
    }
    return AVAILABLE_SUBTITLE_LANGUAGES.find { it.code.equals(code, ignoreCase = true) }?.displayName
        ?: runCatching {
            Locale.forLanguageTag(code).displayLanguage
        }.getOrNull()?.takeIf { it.isNotBlank() }
        ?: code
}
