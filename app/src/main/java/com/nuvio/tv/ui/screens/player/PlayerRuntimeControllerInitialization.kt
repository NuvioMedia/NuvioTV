package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.DolbyVisionCompatibility
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.MatroskaDolbyVisionHookInstaller
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import kotlin.math.min
import kotlinx.coroutines.withTimeoutOrNull

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 10_000L

internal data class StartupSubtitlePreparation(
    val fetchedSubtitles: List<Subtitle>,
    val attachedSubtitles: List<Subtitle>,
    val fetchCompleted: Boolean
)

@androidx.annotation.OptIn(UnstableApi::class)
@OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(url: String, headers: Map<String, String>) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = "No stream URL provided", showLoadingOverlay = false) }
        return
    }

    scope.launch {
        try {
            autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
            resetLoadingOverlayForNewStream()
            playerInitializationStartedAtMs = System.currentTimeMillis()
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            AudioCapabilities.setExperimentalFireOsAudioQuirksEnabled(
                playerSettings.experimentalDtsIecPassthroughEnabled
            )
            _uiState.update {
                it.copy(
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode
                )
            }
            runAfrPreflightIfEnabled(
                url = url,
                headers = headers,
                frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled
            )
            val startupSubtitlePreparation = prepareStartupSubtitles(
                mode = playerSettings.addonSubtitleStartupMode,
                preferredLanguage = playerSettings.subtitleStyle.preferredLanguage,
                secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage
            )
            val useLibass = false // Temporarily disabled for maintenance
            val libassRenderType = playerSettings.libassRenderType.toAssRenderType()
            DoviBridge.resetRuntimeCounters()
            MatroskaDolbyVisionHookInstaller.resetRuntimeCounters()
            val dv7ToDv81Probe = if (playerSettings.experimentalDv7ToDv81Enabled) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = "setting-disabled",
                    bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
                    extractorHookReady = DoviBridge.isExtractorHookReadyInBuild,
                    selfTest = DoviBridge.SelfTestResult(
                        passed = false,
                        reason = "not-run",
                        inputBytes = 0,
                        outputBytes = 0
                    )
                )
            }
            isExperimentalDv7ToDv81ActiveForCurrentPlayback =
                playerSettings.experimentalDv7ToDv81Enabled && dv7ToDv81Probe.supported
            hasAttemptedDv7ToDv81ForCurrentPlayback = false
            dv7ToDv81BridgeVersionForCurrentPlayback = dv7ToDv81Probe.bridgeVersion
            dv7ToDv81LastProbeReasonForCurrentPlayback = dv7ToDv81Probe.reason
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: setting=${playerSettings.experimentalDv7ToDv81Enabled} " +
                    "dv5Compat=${playerSettings.experimentalDv5ToDv81Enabled} " +
                    "preserveMapping=${playerSettings.experimentalDv7ToDv81PreserveMappingEnabled} " +
                    "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
                    "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
                    "extractorHookReady=${dv7ToDv81Probe.extractorHookReady} " +
                    "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                    "reason=${dv7ToDv81Probe.reason} " +
                    "selfTest=${dv7ToDv81Probe.selfTest.reason} " +
                    "bridge=${dv7ToDv81Probe.bridgeVersion ?: "n/a"} " +
                    "host=${url.safeHost()}"
            )
            val bufferSettings = playerSettings.bufferSettings
            val targetBufferBytes = if (bufferSettings.targetBufferSizeMb <= 0) {
                C.LENGTH_UNSET
            } else {
                min(
                    Int.MAX_VALUE.toLong(),
                    bufferSettings.targetBufferSizeMb.toLong() * 1024L * 1024L
                ).toInt()
            }
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    bufferSettings.minBufferMs,
                    bufferSettings.maxBufferMs,
                    bufferSettings.bufferForPlaybackMs,
                    bufferSettings.bufferForPlaybackAfterRebufferMs
                )
                .setTargetBufferBytes(targetBufferBytes)
                .setBackBuffer(
                    bufferSettings.backBufferDurationMs,
                    bufferSettings.retainBackBufferFromKeyframe
                )
                .setPrioritizeTimeOverSizeThresholds(false)
                .build()

            mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
            mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
            mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
            mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
            mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
            val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive

            
            trackSelector = DefaultTrackSelector(context).apply {
                setParameters(
                    buildUponParameters()
                        .setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true)
                )
                if (playerSettings.tunnelingEnabled && !safeAudioModeEnabled) {
                    setParameters(
                        buildUponParameters().setTunnelingEnabled(true)
                    )
                } else if (safeAudioModeEnabled) {
                    setParameters(
                        buildUponParameters()
                            .setTunnelingEnabled(false)
                            .setConstrainAudioChannelCountToDeviceCapabilities(true)
                    )
                }
                if (audioDisabledForStream) {
                    setParameters(
                        buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO))
                    )
                }
                if (vc1TrackSelectionBypassActive) {
                    setParameters(
                        buildUponParameters()
                            .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                            .setExceedVideoConstraintsIfNecessary(true)
                            .setExceedRendererCapabilitiesIfNecessary(true)
                            .setForceHighestSupportedBitrate(true)
                    )
                }

                val deviceLanguages = if (Build.VERSION.SDK_INT >= 24) {
                    val localeList = Resources.getSystem().configuration.locales
                    List(localeList.size()) { localeList[it].isO3Language }
                } else {
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
            val dolbyVisionHookInstalled = MatroskaDolbyVisionHookInstaller.maybeInstall(
                extractorsFactory = extractorsFactory,
                enabled = playerSettings.experimentalDv7ToDv81Enabled,
                allowDv5Conversion = playerSettings.experimentalDv5ToDv81Enabled,
                preserveMappingEnabled = playerSettings.experimentalDv7ToDv81PreserveMappingEnabled,
                streamUrl = url
            )
            if (dolbyVisionHookInstalled) {
                isExperimentalDv7ToDv81ActiveForCurrentPlayback = true
                if (dv7ToDv81LastProbeReasonForCurrentPlayback != "ready") {
                    dv7ToDv81LastProbeReasonForCurrentPlayback = "extractor-hook-enabled"
                }
            }
            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback && !dolbyVisionHookInstalled) {
                isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
                dv7ToDv81LastProbeReasonForCurrentPlayback =
                    "extractor-hook-install-failed"
            }
            if (playerSettings.experimentalDv7ToDv81Enabled) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_DOVI: extractorHookInstalled=$dolbyVisionHookInstalled " +
                        "active=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
                        "host=${url.safeHost()}"
                )
            }

            
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)
            val mapDv7ToHevcEnabled =
                playerSettings.mapDV7ToHevc || dv7ToHevcForcedStreamUrls.contains(url)
            DolbyVisionCompatibility.setMapDv7ToHevcEnabled(mapDv7ToHevcEnabled)
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            val codecSelector = createDolbyVisionFallbackCodecSelector(mapDv7ToHevcEnabled)
            val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
            isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
            val effectiveDecoderPriority = if (vc1SoftwareFallbackActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                playerSettings.decoderPriority
            }
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                safeAudioModeEnabled = safeAudioModeEnabled
            )
                .setExtensionRendererMode(effectiveDecoderPriority)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)
                .applyMapDv7ToHevcIfSupported(mapDv7ToHevcEnabled)
            Log.i(
                PlayerRuntimeController.TAG,
                "VIDEO_PATH: decoderMode=${describeExtensionRendererMode(effectiveDecoderPriority)} " +
                    "vc1FallbackActive=$vc1SoftwareFallbackActive " +
                    "vc1TrackBypassActive=$vc1TrackSelectionBypassActive " +
                    "host=${url.safeHost()}"
            )

            _exoPlayer = if (useLibass) {
                
                ExoPlayer.Builder(context)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .buildWithAssSupport(
                        context = context,
                        renderType = libassRenderType,
                        renderersFactory = renderersFactory
                    )
            } else {
                
                ExoPlayer.Builder(context)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(context, extractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .build()
            }

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
                attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles
                    .distinctBy { addonSubtitleKey(it) }
                    .map(::addonSubtitleKey)
                    .toSet()
                val startupSubtitleConfigurations = startupSubtitlePreparation.attachedSubtitles
                    .distinctBy { "${it.id}|${it.url}" }
                    .map { subtitle -> toSubtitleConfiguration(subtitle) }
                setMediaSource(
                    mediaSourceFactory.createMediaSource(
                        url = url,
                        headers = headers,
                        subtitleConfigurations = startupSubtitleConfigurations
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
                        if (playbackState == Player.STATE_BUFFERING &&
                            pendingSeekTelemetryAwaitingFirstFrame &&
                            pendingSeekTelemetryReadyAssumed
                        ) {
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryReadyAssumed = false
                        }
                    
                        
                        if (playbackState == Player.STATE_READY) {
                            if (
                                pendingSeekTelemetryRequestedAtMs > 0L &&
                                    pendingSeekTelemetryReadyAtMs <= 0L
                            ) {
                                val latencyMs =
                                    (System.currentTimeMillis() - pendingSeekTelemetryRequestedAtMs)
                                        .coerceAtLeast(0L)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "SEEK_READY: latencyMs=$latencyMs " +
                                        "targetMs=$pendingSeekTelemetryTargetMs " +
                                        "host=${currentStreamUrl.safeHost()}"
                                )
                                pendingSeekTelemetryReadyAtMs = System.currentTimeMillis()
                                pendingSeekTelemetryReadyLatencyMs = latencyMs
                            }
                            if (!hasRenderedFirstFrame) {
                                _uiState.update { state ->
                                    state.copy(
                                        showLoadingOverlay = false,
                                        showControls = true
                                    )
                                }
                            }
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
                            maybeScheduleFirstFrameWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
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
                        cancelFirstFrameWatchdog()
                        mediaSourceFactory.notifyPlaybackFirstFrameRendered()
                        val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs)
                            .coerceAtLeast(0L)
                        val conversionCalls = DoviBridge.getConversionCallCount()
                        val conversionSucceeded = DoviBridge.getConversionSuccessCount()
                        val signalingRewrites =
                            MatroskaDolbyVisionHookInstaller.getCodecStringRewriteCount()
                        val sourceProfile =
                            MatroskaDolbyVisionHookInstaller.getLastDetectedSourceProfile()
                        val conversionMode =
                            MatroskaDolbyVisionHookInstaller.getLastSelectedConversionMode()
                        val conversionAttempted =
                            hasAttemptedDv7ToDv81ForCurrentPlayback ||
                                conversionCalls > 0 ||
                                signalingRewrites > 0
                        if (
                            pendingSeekTelemetryAwaitingFirstFrame &&
                                pendingSeekTelemetryRequestedAtMs > 0L
                        ) {
                            val now = System.currentTimeMillis()
                            val totalLatencyMs =
                                (now - pendingSeekTelemetryRequestedAtMs).coerceAtLeast(0L)
                            val readyToFirstFrameMs =
                                if (pendingSeekTelemetryReadyAtMs > 0L) {
                                    (now - pendingSeekTelemetryReadyAtMs).coerceAtLeast(0L)
                                } else {
                                    -1L
                                }
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "SEEK_FIRST_FRAME: totalLatencyMs=$totalLatencyMs " +
                                    "readyLatencyMs=$pendingSeekTelemetryReadyLatencyMs " +
                                    "readyToFirstFrameMs=$readyToFirstFrameMs " +
                                    "targetMs=$pendingSeekTelemetryTargetMs " +
                                    "host=${currentStreamUrl.safeHost()}"
                            )
                            pendingSeekTelemetryRequestedAtMs = 0L
                            pendingSeekTelemetryTargetMs = -1L
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryAwaitingFirstFrame = false
                        }
                        if (!hasRenderedFirstFrame) {
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "PLAYBACK_STARTUP: firstFrameMs=$startupMs " +
                                    "dv7doviActive=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
                                    "dv7doviAttempted=$conversionAttempted " +
                                    "dvSourceProfile=${sourceProfile ?: "n/a"} " +
                                    "dvConvertMode=${conversionMode ?: "n/a"} " +
                                    "dv7doviSignalRewrites=$signalingRewrites " +
                                    "dv7doviCalls=$conversionCalls " +
                                    "dv7doviSuccess=$conversionSucceeded " +
                                    "dv7doviReason=${dv7ToDv81LastProbeReasonForCurrentPlayback ?: "n/a"} " +
                                    "dv7doviBridge=${dv7ToDv81BridgeVersionForCurrentPlayback ?: "n/a"} " +
                                    "dv7hevcActive=$isMapDv7ToHevcActiveForCurrentPlayback " +
                                    "host=${currentStreamUrl.safeHost()}"
                            )
                        }
                        hasRenderedFirstFrame = true
                        _uiState.update { it.copy(showLoadingOverlay = false) }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        cancelFirstFrameWatchdog()
                        if (error.isDolbyVisionDecoderFailure() &&
                            !isMapDv7ToHevcActiveForCurrentPlayback
                        ) {
                            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                                !hasAttemptedDv7ToDv81ForCurrentPlayback
                            ) {
                                hasAttemptedDv7ToDv81ForCurrentPlayback = true
                                val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
                                dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
                                dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "DV7_DOVI: conversion path not applied " +
                                        "reason=${probe.reason} " +
                                        "selfTest=${probe.selfTest.reason} " +
                                        "extractorHookReady=${probe.extractorHookReady} " +
                                        "bridge=${probe.bridgeVersion ?: "n/a"} " +
                                        "host=${currentStreamUrl.safeHost()}"
                                )
                            }

                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Dolby Vision decoder failure detected, retrying with DV7->HEVC " +
                                    "fallback host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition " +
                                    "dv7doviActive=$isExperimentalDv7ToDv81ActiveForCurrentPlayback " +
                                    "dv7doviAttempted=$hasAttemptedDv7ToDv81ForCurrentPlayback " +
                                    "dv7doviReason=${dv7ToDv81LastProbeReasonForCurrentPlayback ?: "n/a"}"
                            )
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
                        }

                        if (error.isAudioTrackInitializationFailure()) {
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "AudioTrack init failed, retrying with safe audio mode " +
                                        "host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "AudioTrack init still failing in safe audio mode, retrying " +
                                        "with audio disabled host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        if (error.isStuckPlayingNoProgress()) {
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "Stuck player detected, retrying with safe audio mode " +
                                        "host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                Log.w(
                                    PlayerRuntimeController.TAG,
                                    "Stuck player persists in safe audio mode, retrying with " +
                                        "audio disabled host=${currentStreamUrl.safeHost()} " +
                                        "positionMs=$currentPosition"
                                )
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        val timeoutError = error.findCause<SocketTimeoutException>()
                        if (timeoutError != null &&
                            timeoutRecoveryAttempts < PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Timeout source error code=${error.errorCode} " +
                                    "attempt=${timeoutRecoveryAttempts + 1}/" +
                                    "${PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS} " +
                                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterTimeout(currentPosition)
                            return
                        }

                        if (error.isUnexpectedLoaderNullPointer() &&
                            !hasRetriedCurrentStreamAfterUnexpectedNpe
                        ) {
                            hasRetriedCurrentStreamAfterUnexpectedNpe = true
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Unexpected source NPE detected, retrying stream once " +
                                    "host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterUnexpectedNpe(currentPosition)
                            return
                        }

                        if (error.isMediaPeriodHolderStateCrash() &&
                            !hasRetriedCurrentStreamAfterMediaPeriodHolderCrash
                        ) {
                            hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "MediaPeriodHolder state crash detected, retrying stream once " +
                                    "host=${currentStreamUrl.safeHost()} " +
                                    "positionMs=$currentPosition"
                            )
                            retryCurrentStreamAfterMediaPeriodHolderCrash(currentPosition)
                            return
                        }

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
            when {
                startupSubtitlePreparation.fetchCompleted -> {
                    _uiState.update {
                        it.copy(
                            addonSubtitles = startupSubtitlePreparation.fetchedSubtitles,
                            isLoadingAddonSubtitles = false,
                            addonSubtitlesError = null
                        )
                    }
                }
                else -> fetchAddonSubtitles()
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

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    cancelFirstFrameWatchdog()
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasAttemptedDv7ToDv81ForCurrentPlayback = false
    isExperimentalDv7ToDv81ActiveForCurrentPlayback = false
    isVc1SoftwareFallbackActiveForCurrentPlayback = false
    isVc1TrackSelectionBypassActiveForCurrentPlayback = false
    isSafeAudioModeActiveForCurrentPlayback = false
    isAudioDisabledForCurrentPlayback = false
    dv7ToDv81BridgeVersionForCurrentPlayback = null
    dv7ToDv81LastProbeReasonForCurrentPlayback = null
    playerInitializationStartedAtMs = 0L
    pendingSeekTelemetryRequestedAtMs = 0L
    pendingSeekTelemetryTargetMs = -1L
    pendingSeekTelemetryReadyAtMs = 0L
    pendingSeekTelemetryReadyLatencyMs = -1L
    pendingSeekTelemetryAwaitingFirstFrame = false
    pendingSeekTelemetryReadyAssumed = false
    lastKnownDuration = 0L
    currentStreamHasVideoTrack = false
    currentVideoTrackIsLikelyVc1 = false
    currentVideoTrackMimeType = null
    currentVideoTrackCodecs = null
    currentVideoTrackWidth = 0
    currentVideoTrackHeight = 0
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
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
    private val safeAudioModeEnabled: Boolean
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParams: Boolean
    ): AudioSink {
        if (!safeAudioModeEnabled) {
            return DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(enableFloatOutput)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .build()
        }
        val filteredCapabilities = buildStableAudioCapabilities(context)
        return DefaultAudioSink.Builder()
            .setAudioCapabilities(filteredCapabilities)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .build()
    }

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

private inline fun <reified T : Throwable> Throwable.findCause(): T? {
    var current: Throwable? = this
    while (current != null) {
        if (current is T) return current
        current = current.cause
    }
    return null
}

private fun PlaybackException.isDolbyVisionDecoderFailure(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_DECODING_FAILED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("dolby-vision", ignoreCase = true) &&
        details.contains("decoder failed", ignoreCase = true)
}

private fun PlaybackException.isUnexpectedLoaderNullPointer(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_IO_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("unexpected nullpointerexception", ignoreCase = true) ||
        (details.contains("nullpointerexception", ignoreCase = true) &&
            details.contains("matroskaextractor", ignoreCase = true))
}

private fun PlaybackException.isAudioTrackInitializationFailure(): Boolean {
    if (errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return true
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("audiotrack init failed", ignoreCase = true)
}

private fun PlaybackException.isStuckPlayingNoProgress(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_TIMEOUT) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("stuck playing with no progress", ignoreCase = true)
}

private fun PlaybackException.isMediaPeriodHolderStateCrash(): Boolean {
    if (errorCode != PlaybackException.ERROR_CODE_UNSPECIFIED) return false
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return details.contains("mediaperiodholder", ignoreCase = true) &&
        details.contains(".info", ignoreCase = true) &&
        details.contains("null", ignoreCase = true)
}

private fun String.safeHost(): String {
    return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
}

private fun createDolbyVisionFallbackCodecSelector(
    forceHdr10Fallback: Boolean
): MediaCodecSelector {
    if (!forceHdr10Fallback) return MediaCodecSelector.DEFAULT

    return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
        if (mimeType == MimeTypes.VIDEO_DOLBY_VISION) {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                MimeTypes.VIDEO_H265,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
        } else {
            MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType,
                requiresSecureDecoder,
                requiresTunnelingDecoder
            )
        }
    }
}

private fun describeExtensionRendererMode(mode: Int): String {
    return when (mode) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "off"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "on"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "prefer"
        else -> mode.toString()
    }
}

private fun DefaultRenderersFactory.applyMapDv7ToHevcIfSupported(
    enabled: Boolean
): DefaultRenderersFactory {
    return runCatching {
        val method = javaClass.getMethod("setMapDV7ToHevc", Boolean::class.javaPrimitiveType)
        method.invoke(this, enabled)
        this
    }.getOrElse { this }
}

private fun buildStableAudioCapabilities(context: Context): AudioCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    val supportedEncodings = mutableListOf<Int>()
    val knownEncodings = intArrayOf(
        C.ENCODING_PCM_16BIT,
        C.ENCODING_AC3,
        C.ENCODING_AC4,
        C.ENCODING_DTS,
        C.ENCODING_E_AC3_JOC,
        C.ENCODING_E_AC3,
        C.ENCODING_DOLBY_TRUEHD
    )
    for (encoding in knownEncodings) {
        if (detected.supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    // Force DTS-HD/DTS:X passthrough down to DTS core. This avoids AudioTrack init failures on
    // devices that advertise DTS-HD direct playback but fail to initialize the encoded sink.
    if ((detected.supportsEncoding(C.ENCODING_DTS_HD) ||
            detected.supportsEncoding(C.ENCODING_DTS_UHD_P2)) &&
        C.ENCODING_DTS !in supportedEncodings
    ) {
        supportedEncodings += C.ENCODING_DTS
    }
    return AudioCapabilities(
        supportedEncodings.toIntArray(),
        detected.maxChannelCount
    )
}
