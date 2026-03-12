package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
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
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

private suspend fun PlayerRuntimeController.resolveCurrentStreamMimeType(
    url: String,
    headers: Map<String, String>
) {
    currentStreamMimeType = PlayerMediaSourceFactory.probeMimeType(
        url = url,
        headers = headers,
        filename = currentFilename
    )
    Log.d(
        PlayerRuntimeController.TAG,
        "Resolved stream mimeType=${currentStreamMimeType ?: "unknown"} for url=$url"
    )
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(url: String, headers: Map<String, String>) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = "No stream URL provided", showLoadingOverlay = false) }
        return
    }

    scope.launch {
        try {
            resetLoadingOverlayForNewStream()
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            _uiState.update {
                it.copy(
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode
                )
            }
            runAfrPreflightIfEnabled(
                url = url,
                headers = headers,
                frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
            )
            resolveCurrentStreamMimeType(
                url = url,
                headers = headers
            )
            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings)
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
                    DefaultLoadControl.DEFAULT_BUFFER_FOR_PLAYBACK_AFTER_REBUFFER_MS
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

                val deviceLanguages = if (Build.VERSION.SDK_INT >= 24) {
                    val localeList = Resources.getSystem().configuration.locales
                    List(localeList.size()) { localeList[it].isO3Language }
                } else {
                    @Suppress("DEPRECATION")
                    listOf(Resources.getSystem().configuration.locale.isO3Language)
                }
                val preferredAudioLanguages = resolvePreferredAudioLanguages(
                    preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                    secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                    deviceLanguages = deviceLanguages
                )
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
                subtitleDelayUsProvider = subtitleDelayUs::get
            ).setExtensionRendererMode(playerSettings.decoderPriority)
                .setMapDV7ToHevc(playerSettings.mapDV7ToHevc)

            val buildDefaultPlayer = {
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory = null,
                    subtitleParserFactory = null
                )
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .build()
            }

            _exoPlayer = if (useLibass) {
                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
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

                try {
                    loudnessEnhancer?.release()
                    loudnessEnhancer = LoudnessEnhancer(audioSessionId)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                
                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                applyStartupSubtitlePreparation(startupSubtitlePreparation)
                val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
                setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        url = url,
                        headers = headers,
                        subtitleConfigurations = startupSubtitleConfigurations,
                        mimeTypeOverride = currentStreamMimeType
                    )
                )
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
                                    state.copy(showLoadingOverlay = true, showControls = false)
                                } else {
                                    state
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
                        _uiState.update { it.copy(showLoadingOverlay = false) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        val detailedError = buildString {
                            append(error.message ?: "Playback error")
                            val cause = error.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                append(" (HTTP ${cause.responseCode})")
                            } else if (cause != null) {
                                append(": ${cause.message}")
                            }
                            append(" [${error.errorCode}]")
                        }
                        val responseCode =
                            (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
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
            _uiState.update {
                it.copy(
                    error = e.message ?: "Failed to initialize player",
                    showLoadingOverlay = false
                )
            }
        }
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>
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
        else -> listOfNotNull(
            normalize(preferredAudioLanguage),
            normalize(secondaryPreferredAudioLanguage)
        ).distinct()
    }
}

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(
    mode: AddonSubtitleStartupMode,
    preferredLanguage: String,
    secondaryLanguage: String?
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
        fetchAddonSubtitlesNow()
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
    autoSubtitleSelected = false
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    attachedAddonSubtitleKeys = emptySet()
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
    playerSettings: PlayerSettings
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
        secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage
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
    private val subtitleDelayUsProvider: () -> Long
) : DefaultRenderersFactory(context) {

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val startIndex = out.size
        super.buildTextRenderers(context, output, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(out[index], subtitleDelayUsProvider)
        }
    }
}

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val offset = subtitleDelayUsProvider()
        val adjustedPositionUs = (positionUs - offset).coerceAtLeast(0L)
        
        super.render(adjustedPositionUs, elapsedRealtimeUs)
    }
}
