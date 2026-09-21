package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.data.local.AVAILABLE_SUBTITLE_LANGUAGES
import com.nuvio.tv.data.local.SubtitleLanguageOption
import com.nuvio.tv.data.local.displayName
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
            Log.w(PlayerRuntimeController.TAG, "AI subtitle source has no extractable text; disabling translation")
            setAiSubtitleTranslationEnabled(false)
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
            val shouldBeActive = canUseAi && (
                manager.isEnabled ||
                    (style.aiAutoSelect && !aiSubtitleAutoSelectAttempted) ||
                    _uiState.value.aiSubtitleTranslationActive
                )

            if (style.aiAutoSelect && canUseAi && !aiSubtitleAutoSelectAttempted) {
                aiSubtitleAutoSelectAttempted = true
                setAiSubtitleTranslationEnabled(true)
            } else if (!canUseAi && manager.isEnabled) {
                setAiSubtitleTranslationEnabled(false)
            }

            _uiState.update {
                it.copy(
                    aiSubtitleAvailable = canUseAi,
                    aiSubtitleTranslationActive = manager.isEnabled && canUseAi,
                    subtitleAiFeatureEnabled = style.aiEnabled
                )
            }

            // Keep shouldBeActive referenced for clarity in future expansions.
            @Suppress("UNUSED_EXPRESSION")
            shouldBeActive
        }
    }
}

internal fun PlayerRuntimeController.setAiSubtitleTranslationEnabled(enabled: Boolean) {
    if (enabled && isUsingMpvEngine()) {
        Log.i(PlayerRuntimeController.TAG, "AI subtitle translation ignored on MPV")
        return
    }
    val manager = ensureSubtitleTranslationManager()
    val apiKeyOk = subtitleAiApiKey.isNotBlank()
    val featureOk = subtitleAiFeatureEnabled
    val effective = enabled && apiKeyOk && featureOk && !isUsingMpvEngine()
    if (manager.isEnabled == effective) {
        _uiState.update {
            it.copy(
                aiSubtitleTranslationActive = effective,
                aiSubtitleAvailable = !isUsingMpvEngine() && featureOk && apiKeyOk
            )
        }
        return
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
    Log.i(PlayerRuntimeController.TAG, "AI subtitle translation enabled=$effective")
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
