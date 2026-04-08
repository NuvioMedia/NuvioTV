package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.os.Build
import android.os.Handler
import android.util.Log
import com.nuvio.tv.BuildConfig
import com.nuvio.tv.R
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioTrackAudioOutputProvider
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L
private const val MPV_AFR_SETTLE_DELAY_MS = 2_000L

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

private suspend fun PlayerRuntimeController.resolveCurrentStreamMimeType(
    url: String,
    headers: Map<String, String>
) {
    currentStreamMimeType?.let { resolvedMimeType ->
        Log.d(
            PlayerRuntimeController.TAG,
            "Resolved stream mimeType=$resolvedMimeType for url=$url"
        )
        return
    }
    currentStreamMimeType = PlayerMediaSourceFactory.probeMimeType(
        url = url,
        headers = headers,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} for url=$url"
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true
) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = context.getString(R.string.player_error_no_stream_url), showLoadingOverlay = false) }
        return
    }

    scope.launch {
        try {
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            resetLoadingOverlayForNewStream()
            hasTriedAudioPcmFallback = false
            hasTriedDv7HevcFallback = false
            mpvDelayStartAfterAfrSwitch = false
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            cachedDecoderPriority = playerSettings.decoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
            mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
            val effectiveInternalPlayerEngine = overrideInternalPlayerEngine ?: playerSettings.internalPlayerEngine
            runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
            currentInternalPlayerEngine = effectiveInternalPlayerEngine
            val showLoadingStatus = playerSettings.showPlayerLoadingStatus
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode,
                    tunnelingEnabled = playerSettings.tunnelingEnabled,
                    autoTranslateSubtitles = playerSettings.autoTranslateSubtitles,
                    subtitleTranslationAvailable = playerSettings.subtitleAiEnabled,
                    removeHearingImpaired = playerSettings.subtitleRemoveHearingImpaired,
                    loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_detecting_format) else null
                )
            }
            val afrJob = async {
                runAfrPreflightIfEnabled(
                    url = url,
                    headers = headers,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
                )
            }
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                mpvInitializationInProgress = true
                try {
                    afrJob.await()
                    if (mpvDelayStartAfterAfrSwitch) {
                        Log.d(
                            PlayerRuntimeController.TAG,
                            "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms"
                        )
                        delay(MPV_AFR_SETTLE_DELAY_MS)
                    }
                    initializeMpvPlayer(
                        url = url,
                        headers = headers,
                        allowEngineFailover = allowEngineFailover
                    )
                    // Keep addon subtitle discovery available on the mpv path too.
                    // Exo does this later in this method, but this branch returns early.
                    fetchAddonSubtitles()
                } finally {
                    mpvInitializationInProgress = false
                }
                return@launch
            }
            resolveCurrentStreamMimeType(
                url = url,
                headers = headers
            )
            mpvInitializationInProgress = false
            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings, showLoadingStatus)
            afrJob.await()
            requestedUseLibassByUser = playerSettings.useLibass
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> true
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = when {
                !useLibass -> requestedLibassRenderType
                requestedLibassRenderType == AssRenderType.OVERLAY_OPEN_GL -> AssRenderType.EFFECTS_OPEN_GL
                requestedLibassRenderType == AssRenderType.OVERLAY_CANVAS -> AssRenderType.EFFECTS_CANVAS
                else -> requestedLibassRenderType
            }
            val loadControl = DefaultLoadControl.Builder()
                .setTargetBufferBytes(100 * 1024 * 1024)
                .setBufferDurationsMs(
                    DefaultLoadControl.DEFAULT_MIN_BUFFER_MS,
                    70_000,
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_MS,
                    5_000
                )
                .build()

            
            trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                )
                if (playerSettings.tunnelingEnabled) {
                    setParameters(
                        buildUponParameters().setTunnelingEnabled(true)
                    )
                }

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(
                        buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray())
                    )
                }

                
                val appContext = this@initializePlayer.context
                val captioningManager = appContext.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(
                            buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT)
                        )
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(
                            buildUponParameters().setPreferredTextLanguage(locale.isO3Language)
                        )
                    }
                }
            }

            
            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

            
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                shouldNormalizeCuePositionProvider = {
                    val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
                    selectedAddonSubtitle != null &&
                        PlayerSubtitleUtils.mimeTypeFromUrl(selectedAddonSubtitle.url) == MimeTypes.TEXT_VTT
                },
                gainAudioProcessor = gainAudioProcessor,
                translationManager = translationManager,
                translationScope = scope,
                removeHearingImpairedProvider = { _uiState.value.removeHearingImpaired },
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                onPlaybackSpeedAwareAudioOutputProviderCreated = { playbackSpeedAwareAudioOutputProvider = it }
            ).setExtensionRendererMode(playerSettings.decoderPriority)
                .setMapDV7ToHevc(playerSettings.mapDV7ToHevc || forceDv7ToHevc)

            if (showLoadingStatus) _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_building)) }
            val buildDefaultPlayer = {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = null,
                    subtitleParserFactory = null
                )
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, extractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setReleaseTimeoutMs(3000)
                    .build()
            }

            _exoPlayer = if (useLibass) {
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, extractorsFactory))
                    .setReleaseTimeoutMs(3000)
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        dataSourceFactory = playerDataSourceFactory,
                        extractorsFactory = extractorsFactory,
                        renderersFactory = renderersFactory
                    )
            } else {
                buildDefaultPlayer()
            }
            activePlayerUsesLibass = useLibass
            libassPipelineSwitchInFlight = false

            _exoPlayer?.apply {
                
                val audioAttributes = AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build()
                setAudioAttributes(audioAttributes, true)
                playbackSpeedAwareAudioOutputProvider?.updatePlaybackSpeed(
                    _uiState.value.playbackSpeed,
                    selectedAudioRequiresPcmForSpeed(this)
                )
                setPlaybackSpeed(_uiState.value.playbackSpeed)

                
                if (playerSettings.skipSilence) {
                    skipSilenceEnabled = true
                }

                
                setHandleAudioBecomingNoisy(true)

                
                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, this).build()
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAudioAmplification(_uiState.value.audioAmplificationDb)

                
                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                applyStartupSubtitlePreparation(startupSubtitlePreparation)
                val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
                setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        context = context,
                        url = url,
                        headers = headers,
                        subtitleConfigurations = startupSubtitleConfigurations,
                        filename = currentFilename,
                        responseHeaders = currentStreamResponseHeaders,
                        mimeTypeOverride = currentStreamMimeType
                    )
                )
                if (showLoadingStatus) _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_starting)) }
                playWhenReady = true
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) {
                            lastKnownDuration = playerDuration
                        }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        _uiState.update { 
                            it.copy(
                                isBuffering = isBuffering,
                                playbackEnded = playbackState == Player.STATE_ENDED,
                                duration = playerDuration.coerceAtLeast(0L)
                            )
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_buffering) else null)
                                } else {
                                    state.copy(loadingMessage = if (showLoadingStatus) context.getString(R.string.player_loading_buffering) else null)
                                }
                            }
                        }
                    
                        
                        if (playbackState == Player.STATE_READY) {
                            if (shouldEnforceAutoplayOnFirstReady) {
                                shouldEnforceAutoplayOnFirstReady = false
                                if (!userPausedManually && !isPlaying) {
                                    if (!playWhenReady) {
                                        playWhenReady = true
                                    }
                                    play()
                                }
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            // Re-evaluate subtitle auto-selection once player is ready.
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()

                            trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                        }
                    
                        
                        if (playbackState == Player.STATE_ENDED) {
                            emitCompletionScrobbleStop(progressPercent = 99.5f)
                            saveWatchProgress()
                            resetNextEpisodeCardState(clearEpisode = false)
                        }
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        _uiState.update { it.copy(isPlaying = isPlaying) }
                        if (isPlaying) {
                            userPausedManually = false
                            cancelPauseOverlay()
                            startProgressUpdates()
                            startWatchProgressSaving()
                            scheduleHideControls()
                            tryShowParentalGuide()
                            emitScrobbleStart()
                        } else {
                            if (userPausedManually) {
                                schedulePauseOverlay()
                            } else {
                                cancelPauseOverlay()
                            }
                            stopProgressUpdates()
                            stopWatchProgressSaving()
                            if (playbackState != Player.STATE_BUFFERING) {
                                emitStopScrobbleForCurrentProgress()
                            }
                            
                            saveWatchProgress()
                        }
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onRenderedFirstFrame() {
                        hasRenderedFirstFrame = true
                        resetErrorRetryState()
                        // Restore speed after PCM fallback — audio sink is already
                        // configured in PCM mode and won't revert to passthrough.
                        if (hasTriedAudioPcmFallback) {
                            _exoPlayer?.playbackParameters = PlaybackParameters(1f)
                        }
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                showPlayerEngineSwitchInfo = false
                            )
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) {
                            return
                        }
                        val detailedError = error.toDisplayMessage()
                        val responseCode = error.findInvalidResponseCodeException()?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
                            return
                        }
                        if (maybeAutoSwitchInternalPlayerOnStartupError(
                                detailedError = detailedError,
                                allowEngineFailover = allowEngineFailover
                            )
                        ) {
                            return
                        }
                        // Attempt automatic recovery for transient errors.
                        if (tryAudioTrackPcmFallback(error)) {
                            return
                        }
                        if (tryDv7HevcFallback(error)) {
                            return
                        }
                        if (attemptStartupRecovery(error, detailedError)) {
                            return
                        }
                        if (hasRenderedFirstFrame && attemptAutoRetry(error, detailedError)) {
                            return
                        }
                        _uiState.update {
                            it.copy(
                                error = detailedError,
                                showLoadingOverlay = false,
                                showPauseOverlay = false
                            )
                        }
                    }
                })
            }
            if (!startupSubtitlePreparation.fetchCompleted) {
                fetchAddonSubtitles()
            }
        } catch (e: Exception) {
            if (
                maybeAutoSwitchInternalPlayerOnStartupError(
                    detailedError = e.message ?: "Failed to initialize player",
                    allowEngineFailover = allowEngineFailover
                )
            ) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage("Failed to initialize player"),
                    showLoadingOverlay = false
                )
            }
        }
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language
            ?.trim()
            ?.lowercase()
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            AudioLanguageOption.ORIGINAL,
            SUBTITLE_LANGUAGE_FORCED -> null
            else -> normalized
        }
    }

    return when (preferredAudioLanguage.trim().lowercase()) {
        AudioLanguageOption.DEFAULT -> listOfNotNull(
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
        AudioLanguageOption.DEVICE -> (
            deviceLanguages
            .mapNotNull(::normalize)
            + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
            ).distinct()
        AudioLanguageOption.ORIGINAL -> {
            val originalLang = normalize(contentOriginalLanguage)
            if (originalLang != null) {
                listOfNotNull(
                    originalLang,
                    normalize(secondaryPreferredAudioLanguage)
                ).distinct()
            } else {
                // Fallback to device languages when original language is unknown
                (deviceLanguages
                    .mapNotNull(::normalize)
                    + listOfNotNull(normalize(secondaryPreferredAudioLanguage))
                ).distinct()
            }
        }
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal fun resolveDeviceAudioLanguages(): List<String> {
    return if (Build.VERSION.SDK_INT >= 24) {
        val localeList = Resources.getSystem().configuration.locales
        List(localeList.size()) { localeList[it].isO3Language }
    } else {
        listOf(Resources.getSystem().configuration.locale.isO3Language)
    }
}

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(
    mode: AddonSubtitleStartupMode,
    preferredLanguage: String,
    secondaryLanguage: String?,
    showLoadingStatus: Boolean = true
): StartupSubtitlePreparation {
    if (mode == AddonSubtitleStartupMode.FAST_STARTUP) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    if (buildSubtitleFetchRequest() == null) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    val preferredTargets = when (PlayerSubtitleUtils.normalizeLanguageCode(preferredLanguage)) {
        "none" -> listOfNotNull(
            secondaryLanguage
                ?.takeIf { it.isNotBlank() }
        )
        else -> listOfNotNull(
            preferredLanguage,
            secondaryLanguage?.takeIf { it.isNotBlank() }
        )
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }
        .distinct()

    if (mode == AddonSubtitleStartupMode.PREFERRED_ONLY && preferredTargets.isEmpty()) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }

    val fetchedSubtitles = withTimeoutOrNull(STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS) {
        fetchAddonSubtitlesNow(
            onProgress = if (showLoadingStatus) { completed, total, addonName ->
                val msg = if (completed == 0) {
                    context.getString(R.string.player_loading_subtitles_from, total)
                } else if (addonName != null) {
                    context.getString(R.string.player_loading_subtitles_addon, addonName, completed, total)
                } else {
                    context.getString(R.string.player_loading_subtitles_progress, completed, total)
                }
                _uiState.update { it.copy(loadingMessage = msg) }
            } else null
        )
    } ?: return StartupSubtitlePreparation(
        fetchedSubtitles = emptyList(),
        attachedSubtitles = emptyList(),
        fetchCompleted = false
    )

    val attachedSubtitles = when (mode) {
        AddonSubtitleStartupMode.ALL_SUBTITLES -> fetchedSubtitles
        AddonSubtitleStartupMode.PREFERRED_ONLY -> fetchedSubtitles.filter { subtitle ->
            preferredTargets.any { target ->
                PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target)
            }
        }
        AddonSubtitleStartupMode.FAST_STARTUP -> emptyList()
    }

    return StartupSubtitlePreparation(
        fetchedSubtitles = fetchedSubtitles,
        attachedSubtitles = attachedSubtitles,
        fetchCompleted = true
    )
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    logSwitchTrace(
        stage = "reset-addon-state-new-stream",
        message = "autoSubtitleSelectedBefore=$autoSubtitleSelected " +
            "subtitleDisabledByPersistedPreference=$subtitleDisabledByPersistedPreference " +
            "subtitleAddonRestoredByPersistedPreference=$subtitleAddonRestoredByPersistedPreference " +
            "explicitSelectionBefore=${explicitSubtitleSelectionForEngineSwitch?.selection?.javaClass?.simpleName ?: "none"} " +
            "effectiveSelectionBefore=${effectiveSubtitleSelectionForEngineSwitch?.selection?.javaClass?.simpleName ?: "none"}"
    )
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
    attachedAddonSubtitleKeys = emptySet()
    logSwitchTrace(
        stage = "reset-addon-state-new-stream",
        message = "autoSubtitleSelectedAfter=$autoSubtitleSelected explicitSelectionAfter=none effectiveSelectionAfter=none"
    )
    _uiState.update {
        it.copy(
            addonSubtitles = emptyList(),
            selectedAddonSubtitle = null,
            selectedSubtitleTrackIndex = -1,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

internal suspend fun PlayerRuntimeController.prepareStreamStartSubtitles(
    playerSettings: PlayerSettings,
    showLoadingStatus: Boolean = true
): StartupSubtitlePreparation {
    requestedUseLibassByUser = playerSettings.useLibass
    if (libassPipelineDecisionStreamUrl != currentStreamUrl) {
        libassPipelineDecisionStreamUrl = currentStreamUrl
        libassPipelineOverrideForCurrentStream = null
        libassPipelineSwitchInFlight = false
        hasDetectedAssSsaTrackForCurrentStream = false
    }
    resetAddonSubtitleStateForNewStream()
    return prepareStartupSubtitles(
        mode = playerSettings.addonSubtitleStartupMode,
        preferredLanguage = playerSettings.subtitleStyle.preferredLanguage,
        secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
        showLoadingStatus = showLoadingStatus
    )
}

internal fun PlayerRuntimeController.applyStartupSubtitlePreparation(
    startupSubtitlePreparation: StartupSubtitlePreparation
) {
    attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles
        .distinctBy { addonSubtitleKey(it) }
        .map(::addonSubtitleKey)
        .toSet()
    if (!startupSubtitlePreparation.fetchCompleted) return

    _uiState.update {
        it.copy(
            addonSubtitles = startupSubtitlePreparation.fetchedSubtitles,
            isLoadingAddonSubtitles = false,
            addonSubtitlesError = null
        )
    }
}

internal fun PlayerRuntimeController.buildStartupSubtitleConfigurations(
    startupSubtitlePreparation: StartupSubtitlePreparation
): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return startupSubtitlePreparation.attachedSubtitles
        .distinctBy { "${it.id}|${it.url}" }
        .map(::toSubtitleConfiguration)
}

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    lastKnownDuration = 0L
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false
        )
    }
}

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val gainAudioProcessor: GainAudioProcessor,
    private val translationManager: SubtitleTranslationManager?,
    private val translationScope: CoroutineScope,
    private val removeHearingImpairedProvider: () -> Boolean = { false },
    private val playbackSpeedProvider: () -> Float,
    private val onPlaybackSpeedAwareAudioOutputProviderCreated: (PlaybackSpeedAwareAudioOutputProvider) -> Unit
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val baseAudioOutputProvider = AudioTrackAudioOutputProvider.Builder(context)
            .setAudioTrackBufferSizeProvider(FormatAwareAudioTrackBufferProvider())
            .setMaxPlaybackSpeed(PLAYBACK_SPEEDS.maxOrNull() ?: 2f)
            .build()
        val audioOutputProvider = PlaybackSpeedAwareAudioOutputProvider(baseAudioOutputProvider)
        audioOutputProvider.updatePlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioOutputProviderCreated(audioOutputProvider)

        return DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
            .setAudioOutputProvider(audioOutputProvider)
            .build()
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider
        )
        val translatingOutput = if (translationManager != null) {
            TranslatingTextOutput(
                delegate = normalizingOutput,
                manager = translationManager,
                outputLooper = outputLooper,
                scope = translationScope,
                removeHearingImpairedProvider = removeHearingImpairedProvider
            )
        } else null
        val effectiveOutput: TextOutput = translatingOutput ?: normalizingOutput
        val startIndex = out.size
        super.buildTextRenderers(context, effectiveOutput, outputLooper, extensionRendererMode, out)
        val offsetRenderers = mutableListOf<SubtitleOffsetRenderer>()
        for (index in startIndex until out.size) {
            val offsetRenderer = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
                translationManager = translationManager,
                translationScope = translationScope
            )
            offsetRenderers.add(offsetRenderer)
            out[index] = offsetRenderer
        }
        // When first cue arrives on the playback thread, try each renderer — the one whose
        // baseRenderer is the active TextRenderer will have the subtitle field populated.
        translatingOutput?.onFirstCueOnPlaybackThread = {
            offsetRenderers.forEach { it.triggerPreTranslation() }
        }
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        if (!shouldNormalizeCuePositionProvider()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(CueGroup(cueGroup.cues.map(::normalizeCuePosition), cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        if (!shouldNormalizeCuePositionProvider()) {
            delegate.onCues(cues)
            return
        }
        delegate.onCues(cues.map(::normalizeCuePosition))
    }

    private fun normalizeCuePosition(cue: Cue): Cue {
        if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
            return cue
        }
        return cue.buildUpon()
            .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
            .setLineAnchor(Cue.TYPE_UNSET)
            .build()
    }
}

private class TranslatingTextOutput(
    private val delegate: TextOutput,
    private val manager: SubtitleTranslationManager,
    outputLooper: android.os.Looper,
    private val scope: CoroutineScope,
    private val removeHearingImpairedProvider: () -> Boolean = { false }
) : TextOutput {

    private val handler = Handler(outputLooper)
    @Volatile private var lastCueGroup: CueGroup? = null
    private val seenTexts = LinkedHashSet<String>()
    /** Called once on the playback thread when the first non-empty cue group arrives. */
    var onFirstCueOnPlaybackThread: (() -> Unit)? = null
    private var hasFiredfirstCue = false

    override fun onCues(cueGroup: CueGroup) {
        lastCueGroup = cueGroup
        val cues = cueGroup.cues
        // Fire once on playback thread while subtitle field is populated inside TextRenderer.render()
        if (!hasFiredfirstCue && cues.isNotEmpty()) {
            hasFiredfirstCue = true
            onFirstCueOnPlaybackThread?.invoke()
            onFirstCueOnPlaybackThread = null
        }
        if (!manager.isEnabled) {
            if (cues.isNotEmpty()) Log.d("SubtitleTranslation", "onCues: manager disabled, passing through ${cues.size} cues")
            delegate.onCues(cueGroup)
            return
        }
        if (cues.isEmpty()) {
            delegate.onCues(cueGroup)
            return
        }
        val text = extractText(cues)
        Log.d("SubtitleTranslation", "onCues: manager enabled, text=\"${text.take(80)}\"")
        if (text.isBlank()) {
            if (removeHearingImpairedProvider()) {
                delegate.onCues(CueGroup(emptyList(), cueGroup.presentationTimeUs))
            } else {
                delegate.onCues(cueGroup)
            }
            return
        }

        if (SubtitleTranslationManager.MOCK_MODE) {
            // Synchronous mock: immediately replace each line, timing driven by ExoPlayer
            val mockText = mockTranslate(text)
            delegate.onCues(buildTranslated(cueGroup, cues, mockText))
            return
        }

        val cached = manager.getCached(text)
        if (cached != null) {
            // Already translated — show Hebrew instantly
            delegate.onCues(buildTranslated(cueGroup, cues, cached))
        } else {
            // Show original while API translates, then replace once translation arrives
            delegate.onCues(cueGroup)
            val captured = cueGroup
            scope.launch {
                val translated = manager.translate(text)
                handler.post {
                    if (lastCueGroup === captured) {
                        delegate.onCues(buildTranslated(captured, cues, translated))
                    }
                }
            }
        }
    }

    @Deprecated("Uses the deprecated Media3 callback.")
    override fun onCues(cues: List<Cue>) {
        if (!manager.isEnabled || cues.isEmpty()) {
            delegate.onCues(cues)
            return
        }
        val text = extractText(cues)
        if (text.isBlank()) {
            // If HI removal is on and result is blank, the whole cue was hearing-impaired
            // content that got stripped — suppress it entirely instead of showing the original.
            if (removeHearingImpairedProvider()) {
                delegate.onCues(emptyList())
            } else {
                delegate.onCues(cues)
            }
            return
        }
        if (SubtitleTranslationManager.MOCK_MODE) {
            delegate.onCues(buildTranslated(cues, mockTranslate(text)))
            return
        }
        // Cache-only — async translation is driven by onCues(CueGroup) to avoid duplicate API calls.
        val cached = manager.getCached(text)
        if (cached != null) {
            delegate.onCues(buildTranslated(cues, cached))
        } else {
            delegate.onCues(cues)
        }
    }

    /** Mock: prefix each line with a Hebrew label so we can verify timing and pipeline. */
    private fun mockTranslate(text: String): String =
        text.split("\n").joinToString("\n") { line -> "[מוק] $line" }

    private fun extractText(cues: List<Cue>): String {
        val removeHI = removeHearingImpairedProvider()
        return cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .let { if (removeHI) stripHearingImpaired(it) else it }
    }

    private fun stripHearingImpaired(text: String): String =
        text.replace(Regex("\\[.*?]"), "").trim()

    private fun buildTranslated(group: CueGroup, originalCues: List<Cue>, translatedText: String): CueGroup {
        val translated = applyTranslatedLinesToCues(originalCues, translatedText)
        return CueGroup(translated, group.presentationTimeUs)
    }

    private fun buildTranslated(originalCues: List<Cue>, translatedText: String): List<Cue> =
        applyTranslatedLinesToCues(originalCues, translatedText)

    /**
     * Maps translated lines back to cues, respecting how many lines each original cue contained.
     * A multi-line cue (text with \n) receives the same number of translated lines, so the full
     * translated text is preserved instead of only the first line being applied.
     */
    private fun applyTranslatedLinesToCues(originalCues: List<Cue>, translatedText: String): List<Cue> {
        val translatedLines = translatedText.split("\n")
        var lineIndex = 0
        return originalCues.map { cue ->
            val originalLineCount = (cue.text?.toString() ?: "").split("\n").size
            val end = (lineIndex + originalLineCount).coerceAtMost(translatedLines.size)
            val cueText = if (lineIndex < translatedLines.size) {
                translatedLines.subList(lineIndex, end).joinToString("\n")
            } else {
                cue.text?.toString() ?: ""
            }
            lineIndex += originalLineCount
            cue.buildUpon().setText(cueText).build()
        }
    }
}

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val translationManager: SubtitleTranslationManager? = null,
    private val translationScope: CoroutineScope? = null
) : ForwardingRenderer(baseRenderer) {

    companion object {
        private const val WINDOW_US = 2 * 60 * 1_000_000L      // 2-minute pre-translation window
        private const val PREFETCH_TRIGGER_US = 30 * 1_000_000L // fetch next window 30s before end
        private const val WINDOW_CUES = 80                      // max cues per pre-translation batch (~2-4 min)
    }

    /** Media-time microseconds up to which we have pre-translated. MIN_VALUE = not yet initialized. */
    @Volatile private var preTranslatedUpToUs = Long.MIN_VALUE
    private var currentPositionUs = 0L
    private var lastLookaheadMs = 0L
    private var lastRenderPositionUs = Long.MIN_VALUE

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offset = subtitleDelayUsProvider()
        val adjustedPositionUs = (positionUs - offset).coerceAtLeast(0L)
        val prevPositionUs = lastRenderPositionUs
        // Set currentPositionUs BEFORE super.render() so triggerPreTranslation() sees it
        currentPositionUs = positionUs
        super.render(adjustedPositionUs, elapsedRealtimeUs)
        // Detect seeks: reset window to new position so we immediately pre-translate from there
        if (prevPositionUs != Long.MIN_VALUE &&
            Math.abs(positionUs - prevPositionUs) > 5_000_000L) {
            preTranslatedUpToUs = positionUs
            lastLookaheadMs = 0L
        }
        lastRenderPositionUs = positionUs
        tryPeriodicLookahead()
    }

    /** Checks every 5s for uncached upcoming cues and pre-translates them.
     *  Runs unconditionally — relies on cache filtering to skip already-translated cues. */
    private fun tryPeriodicLookahead() {
        val manager = translationManager ?: return
        if (!manager.isEnabled) return
        val now = System.currentTimeMillis()
        if (now - lastLookaheadMs < 3000L) return
        val allTexts = extractAllCueTexts()
        if (allTexts.isEmpty()) return  // Resolver not populated yet — retry next cycle
        lastLookaheadMs = now
        val toTranslate = allTexts.filter { manager.getCached(it) == null }.take(WINDOW_CUES)
        if (toTranslate.isEmpty()) return
        launchPreTranslation(manager, toTranslate, currentPositionUs + WINDOW_US)
    }


    /**
     * Called from the playback thread via [TranslatingTextOutput.onFirstCueOnPlaybackThread]
     * while TextRenderer.render() has the subtitle field populated. Pre-translates the first
     * batch of upcoming cues (up to WINDOW_CUES).
     */
    fun triggerPreTranslation() {
        val manager = translationManager ?: return
        // Skip if this window is already covered by a prior pre-translation
        if (preTranslatedUpToUs != Long.MIN_VALUE && preTranslatedUpToUs > currentPositionUs + PREFETCH_TRIGGER_US) return
        val allTexts = extractAllCueTexts()
        if (allTexts.isEmpty()) return
        val toTranslate = allTexts.filter { manager.getCached(it) == null }.take(WINDOW_CUES)
        if (toTranslate.isEmpty()) return
        val windowEnd = currentPositionUs + WINDOW_US
        preTranslatedUpToUs = windowEnd
        lastLookaheadMs = System.currentTimeMillis()
        launchPreTranslation(manager, toTranslate, windowEnd)
    }

    private fun launchPreTranslation(manager: SubtitleTranslationManager, texts: List<String>, coveredUpToUs: Long) {
        val tScope = translationScope ?: return
        Log.d("SubtitleTranslation", "Lookahead: pre-translating ${texts.size} cues up to ${coveredUpToUs / 1_000_000L}s")
        tScope.launch {
            manager.preTranslateWindow(texts)
            manager.onLookaheadAdvanced?.invoke(coveredUpToUs / 1000L, texts.size)
        }
    }

    /** Extracts all unique cue texts. Tries newer CuesResolver architecture first, then legacy subtitle field. */
    private fun extractAllCueTexts(): List<String> {
        val texts = mutableSetOf<String>()

        // Newer Media3: TextRenderer uses cuesResolver (MergingCuesResolver)
        try {
            val resolverField = findField(baseRenderer.javaClass, "cuesResolver")
            val resolver = resolverField?.get(baseRenderer)
            if (resolver != null) {
                var extracted = false
                for (candidate in listOf("cuesWithTimingList", "cueGroupsByStartTime", "cueGroups", "cueGroupList", "groups")) {
                    val f = findField(resolver.javaClass, candidate) ?: continue
                    val v = f.get(resolver) ?: continue
                    val count = extractFromCollectionOrMap(v, texts)
                    if (count > 0) {
                        Log.d("SubtitleTranslation", "Lookahead: ${texts.size} texts from CuesResolver.$candidate (size=${if (v is Collection<*>) v.size else "?"})")
                        extracted = true
                        break
                    }
                }
                if (!extracted) {
                    var cls: Class<*>? = resolver.javaClass
                    while (cls != null && cls != Any::class.java) {
                        for (f in cls.declaredFields) {
                            try {
                                f.isAccessible = true
                                val v = f.get(resolver) ?: continue
                                extractFromCollectionOrMap(v, texts)
                            } catch (_: Exception) {}
                        }
                        cls = cls.superclass
                    }
                }
            }
        } catch (e: Exception) {
            Log.w("SubtitleTranslation", "extractAllCueTexts CuesResolver: ${e.message}")
        }

        if (texts.isNotEmpty()) return texts.toList()

        // Legacy Media3: subtitle + nextSubtitle fields (SubtitleOutputBuffer)
        fun extractFromSubtitleField(fieldName: String) {
            try {
                val field = findField(baseRenderer.javaClass, fieldName) ?: return
                val subtitle = field.get(baseRenderer) ?: return
                val getEventTimeCount = subtitle.javaClass.getMethod("getEventTimeCount")
                val getEventTime = subtitle.javaClass.getMethod("getEventTime", Int::class.java)
                val getCues = subtitle.javaClass.getMethod("getCues", Long::class.java)
                val count = getEventTimeCount.invoke(subtitle) as Int
                for (i in 0 until count) {
                    val timeUs = getEventTime.invoke(subtitle, i) as Long
                    @Suppress("UNCHECKED_CAST")
                    val cues = getCues.invoke(subtitle, timeUs) as? List<Cue> ?: continue
                    for (cue in cues) {
                        val text = cue.text?.toString()?.trim()
                        if (!text.isNullOrBlank()) texts.add(text)
                    }
                }
                Log.d("SubtitleTranslation", "extractAllCueTexts: ${texts.size} texts from $fieldName ($count events)")
            } catch (e: Exception) {
                Log.w("SubtitleTranslation", "extractAllCueTexts $fieldName: ${e.message}")
            }
        }
        extractFromSubtitleField("subtitle")
        extractFromSubtitleField("nextSubtitle")  // Next buffered segment — more lookahead

        return texts.toList()
    }

    /** Extracts CueGroup texts from a Map or Collection, returns number of cue groups processed. */
    private fun extractFromCollectionOrMap(v: Any, texts: MutableSet<String>): Int {
        var count = 0
        when (v) {
            is Map<*, *> -> v.values.forEach { extractCueGroupTexts(it, texts).also { if (it) count++ } }
            is Collection<*> -> v.forEach { extractCueGroupTexts(it, texts).also { if (it) count++ } }
        }
        return count
    }

    /** Extracts text from a CueGroup, List<Cue>, or CuesWithTiming-like object. Returns true if anything was processed. */
    private fun extractCueGroupTexts(obj: Any?, texts: MutableSet<String>): Boolean {
        if (obj == null) return false
        if (obj is CueGroup) {
            obj.cues.forEach { cue ->
                val text = cue.text?.toString()?.trim()
                if (!text.isNullOrBlank()) texts.add(text)
            }
            return true
        }
        if (obj is List<*>) {
            obj.filterIsInstance<Cue>().forEach { cue ->
                val text = cue.text?.toString()?.trim()
                if (!text.isNullOrBlank()) texts.add(text)
            }
            return obj.isNotEmpty()
        }
        // CuesWithTiming or similar wrapper — look for a 'cues' field containing List<Cue> or ImmutableList
        try {
            val cuesField = findField(obj.javaClass, "cues")
            val cues = cuesField?.get(obj)
            if (cues is List<*>) {
                cues.filterIsInstance<Cue>().forEach { cue ->
                    val text = cue.text?.toString()?.trim()
                    if (!text.isNullOrBlank()) texts.add(text)
                }
                return cues.isNotEmpty()
            }
        } catch (_: Exception) {}
        return false
    }

    private fun findField(startClass: Class<*>, name: String): java.lang.reflect.Field? {
        var cls: Class<*>? = startClass
        while (cls != null && cls != Any::class.java) {
            try {
                val f = cls.getDeclaredField(name)
                f.isAccessible = true
                return f
            } catch (_: NoSuchFieldException) {}
            cls = cls.superclass
        }
        return null
    }
}
