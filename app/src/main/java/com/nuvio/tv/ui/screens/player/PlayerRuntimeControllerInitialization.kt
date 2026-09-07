package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.graphics.RectF
import android.media.MediaFormat
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.CaptioningManager
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.ffmpeg.FfmpegAudioRenderer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ScrubbingModeParameters
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import com.nuvio.tv.ui.screens.player.iec.IecPassthroughAudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionCodecFallback
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.BitrateAwareLoadControl
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.core.player.AudioPassthroughPolicy
import com.nuvio.tv.core.player.DeniedTranscodePlanner
import com.nuvio.tv.core.player.SurroundFormatResolver
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.DeniedCodecHandling
import com.nuvio.tv.data.local.SurroundChannelTarget
import com.nuvio.tv.data.local.SurroundFormatMode
import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import com.nuvio.tv.domain.model.Subtitle
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import io.github.peerless2012.ass.media.type.AssRenderType
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.net.SocketTimeoutException
import kotlin.math.min
import androidx.media3.common.Tracks


private const val MPV_AFR_SETTLE_DELAY_MS = 2_000L
private const val AUDIO_DELAY_REFRESH_DEBOUNCE_MS = 120L
private const val PLAYER_RELEASE_TIMEOUT_MS = 3000L
private const val PLAYER_REBUILD_SETTLE_DELAY_MS = 120L
private const val ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS = 2_000
private const val ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS = 25_000_000L

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

private fun PlayerRuntimeController.disposeExoPlayerBeforeRebuild() {
    notifyAudioSessionUpdate(false)
    try {
        currentMediaSession?.release()
        currentMediaSession = null
    } catch (_: Exception) {
    }
    _exoPlayer?.let { player ->
        runCatching { player.playWhenReady = false }
        runCatching { player.pause() }
        runCatching { player.stop() }
        runCatching { player.clearMediaItems() }
        runCatching { player.clearVideoSurface() }
        runCatching { player.release() }
    }
    _exoPlayer = null
    playbackSpeedAwareAudioSink = null
}

@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.initializePlayer(
    url: String,
    headers: Map<String, String>,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    allowEngineFailover: Boolean = true,
    startPaused: Boolean = false
) {
    if (url.isEmpty()) {
        _uiState.update { it.copy(error = context.getString(R.string.player_error_no_stream_url), showLoadingOverlay = false) }
        return
    }
    mpvMediaLoadPrepared = false

    scope.launch {
        try {
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
            lastPlaybackDiagnosticsForReport = LastPlaybackDiagnostics.EMPTY
            lastPlaybackIssueError = null
            playbackIssueReportRequestVersion.incrementAndGet()
            playbackAnalyticsDiagnostics.reset()
            PlayerAudioUnderrunCounter.reset()
            PlayerAudioBitrateMeter.reset()
            _uiState.update {
                it.copy(
                    playbackIssueReportStatus = PlaybackIssueReportStatus.Idle,
                    playbackIssueReportId = null,
                    playbackIssueReportError = null
                )
            }
            resetLoadingOverlayForNewStream()
            if (startPaused) {
                userPausedManually = true
                shouldEnforceAutoplayOnFirstReady = false
            }
            val applyPcmFallbackOnStartup = pendingAudioPcmFallbackRebuild
            val applyDv7FallbackOnStartup = forceDv7ToHevc
            if (!applyPcmFallbackOnStartup) {
                hasTriedAudioPcmFallback = false
            }
            hasTriedDv7HevcFallback = false
            forceDv7ToHevc = false
            mpvDelayStartAfterAfrSwitch = false
            playerInitializationStartedAtMs = System.currentTimeMillis()
            // Reset per playback; only the ExoPlayer custom-buffer path sets a real value.
            effectiveBackBufferDurationMs = 0
            currentBitrateAwareLoadControl = null
            configuredBackBufferMs = 0

            val playerSettings = playerSettingsDataStore.playerSettings.first()
            currentPlayerSettingsForReport = playerSettings
            rememberAudioDelayPerDeviceEnabled = playerSettings.rememberAudioDelayPerDevice
            // Always watch output-device changes so Bluetooth connect/disconnect can switch
            // PCM/passthrough policy in place (Media3 1.8.0 BT semantics; do not rebuild).
            registerAudioDelayRouteCallback()
            currentAudioOutputRoute = AudioOutputRouteDetector.detect(context)
            if (rememberAudioDelayPerDeviceEnabled) {
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }
            // Re-verify learned passthrough denials for this route with a background open,
            // once per process per route. Nothing runs when nothing is learned.
            currentAudioOutputRoute?.let { route ->
                if (!route.isBluetooth && _exoPlayer == null) {
                    AudioRejectionReverifier.start(route.key, playerSettings.audioRejectionsConfirmed) { entry ->
                        scope.launch { playerSettingsDataStore.clearAudioRejection(entry) }
                    }
                }
            }
            cachedDecoderPriority = playerSettings.decoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
            mpvHi10pGnextSoftwareFallbackEnabledSetting =
                playerSettings.mpvHi10pGnextSoftwareFallbackEnabled
            mpvHardwareDecodeModeSetting = playerSettings.mpvHardwareDecodeMode
            var effectiveInternalPlayerEngine = overrideInternalPlayerEngine ?: playerSettings.internalPlayerEngine
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.AUTO) {
                effectiveInternalPlayerEngine = resolveAutoInternalPlayerEngine()
            }
            runtimeInternalPlayerEngineOverride = overrideInternalPlayerEngine
            if (overrideInternalPlayerEngine == null && playerSettings.internalPlayerEngine == InternalPlayerEngine.AUTO) {
                resolvedAutoPlayerEngine = effectiveInternalPlayerEngine
            } else if (overrideInternalPlayerEngine != null) {
                resolvedAutoPlayerEngine = null
            }
            currentInternalPlayerEngine = effectiveInternalPlayerEngine
            playbackAnalyticsDiagnostics.setTraceContext(
                host = url.safeHost(),
                engine = effectiveInternalPlayerEngine.name
            )
            playbackAnalyticsDiagnostics.setStartupContext(
                launchStartedAtElapsedMs = launchStartedAtElapsedMs,
                initializationStartedAtWallTimeMs = playerInitializationStartedAtMs,
                startPositionMs = null
            )
            flushPendingPlaybackRawEventLines()
            val deviceAspectMode = deviceLocalPlayerPreferences.aspectMode.first()
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode,
                    aspectMode = deviceAspectMode,
                    playbackIssueReportsEnabled = playerSettings.playbackIssueReportsEnabled,
                    tunnelingEnabled = playerSettings.effectiveTunnelingEnabled &&
                            effectiveInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER
                )
            }
            setLoadingStatus(
                phase = "detecting_format",
                message = context.getString(R.string.player_loading_detecting_format)
            )

            resolveCurrentStreamMimeType(
                url = url,
                headers = headers
            )

            val afrJob = async {
                runAfrPreflightIfEnabled(
                    url = url,
                    headers = headers,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resolutionMatchingEnabled = playerSettings.resolutionMatchingEnabled,
                    mimeType = currentStreamMimeType
                )
            }
            if (effectiveInternalPlayerEngine == InternalPlayerEngine.MVP_PLAYER) {
                mpvInitializationInProgress = true
                try {
                    val awaitedAfr = withTimeoutOrNull(AFR_PREFLIGHT_TOTAL_TIMEOUT_MS) {
                        afrJob.await()
                    }
                    if (awaitedAfr == null) {
                        Log.w(PlayerRuntimeController.TAG, "AFR preflight await timed out in MPV branch; cancelling afrJob")
                        afrJob.cancel()
                    }
                    if (mpvDelayStartAfterAfrSwitch) {
                        Log.d(PlayerRuntimeController.TAG, "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms")
                        delay(MPV_AFR_SETTLE_DELAY_MS)
                    }
                    setLoadingStatus(
                        phase = "mpv_buffering",
                        message = context.getString(R.string.player_loading_buffering)
                    )
                    initializeMpvPlayer(url = url, headers = headers, allowEngineFailover = allowEngineFailover)
                    fetchAddonSubtitles()
                } finally {
                    mpvInitializationInProgress = false
                }
                return@launch
            }
            mpvInitializationInProgress = false

            // ── ExoPlayer Dolby Vision Logic (mode-driven via Dv7HandlingMode) ──
            DoviBridge.resetRuntimeCounters()
            DolbyVisionConversionStats.reset()
            rebufferCount = 0
            rebufferTotalMs = 0L
            rebufferStartedAtMs = 0L

            // Resolve effective DV7 mode — AUTO consults the display-capability policy.
            // The persisted enum stays as-is; only the runtime behavior is derived per playback.
            var effectiveDv7Mode: Dv7HandlingMode
            val dv7AutoResult: DolbyVisionBaseLayerPolicy.Result?
            when (playerSettings.dv7HandlingMode) {
                Dv7HandlingMode.AUTO -> {
                    val result = DolbyVisionBaseLayerPolicy.resolve(
                        context = context,
                        bridgeReady = DoviBridge.isLibraryLoaded
                    )
                    dv7AutoResult = result
                    effectiveDv7Mode = when (result.decision) {
                        DolbyVisionBaseLayerPolicy.Decision.NATIVE_DV7 -> Dv7HandlingMode.OFF
                        DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81 -> Dv7HandlingMode.DV81_LIBDOVI
                        else -> Dv7HandlingMode.HDR10_BASE_LAYER
                    }
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "DV7_AUTO: decision=${result.decision} " +
                                "effectiveMode=$effectiveDv7Mode " +
                                "hdrCapsKnown=${result.hdrCapsKnown} " +
                                "displayDv=${result.displayDv} " +
                                "displayHdr10=${result.displayHdr10} " +
                                "displayHdr10Plus=${result.displayHdr10Plus} " +
                                "codecDvheDtb=${result.codecSupportsDvheDtb} " +
                                "bridgeReady=${result.bridgeReady} " +
                                "api=${result.apiLevel} " +
                                "host=${url.safeHost()}"
                    )
                }
                else -> {
                    dv7AutoResult = null
                    effectiveDv7Mode = playerSettings.dv7HandlingMode
                }
            }

            // Experimental: explicit libdovi conversion-mode override. Only applies
            // when DV7 handling is Convert to DV8.1 (the modes are libdovi conversion
            // modes, so they're only meaningful while conversion is active). Picks
            // which conversion mode runs; -1 (None) uses the auto-selected mode.
            val libdoviModeOverride = playerSettings.dv7LibdoviModeOverride
            val libdoviModeOverrideActive = libdoviModeOverride in 0..4 &&
                    playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI &&
                    effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            if (libdoviModeOverrideActive) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_LIBDOVI_OVERRIDE: forcing conversion mode=$libdoviModeOverride"
                )
            }

            // DV7 to DV8.1 libdovi probe — only runs when the effective mode requests it.
            val dv7ToDv81SettingActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            val dv7ToDv81Probe = if (dv7ToDv81SettingActive) {
                DoviBridge.probeRealtimeConversionSupport(url)
            } else {
                val reason = when (effectiveDv7Mode) {
                    Dv7HandlingMode.HDR10_BASE_LAYER -> "hdr10-base-layer-mode"
                    Dv7HandlingMode.STRIP_DV -> "strip-dv-mode"
                    Dv7HandlingMode.OFF -> "dv7-mode-off"
                    Dv7HandlingMode.AUTO -> "auto-mode-no-dv81"  // unreachable; AUTO is collapsed above
                    Dv7HandlingMode.DV81_LIBDOVI -> "setting-disabled"  // unreachable
                }
                DoviBridge.RealtimeConversionProbe(
                    supported = false,
                    reason = reason,
                    bridgeVersion = DoviBridge.getBridgeVersionOrNull(),
                    extractorHookReady = DoviBridge.isExtractorHookReadyInBuild,
                    selfTest = DoviBridge.SelfTestResult(false, "not-run", 0, 0)
                )
            }
            // A stream that previously failed with conversion armed is forced to the HEVC
            // base layer via dv7ToHevcForcedStreamUrls; that override must also disarm the
            // conversion/extractor path, otherwise the retry rebuilds the exact same broken
            // pipeline (stock extractor + no vendored MKV path) and fails identically.
            val dv7ConversionDisarmedForUrl = dv7ToHevcForcedStreamUrls.contains(url)
            isExperimentalDv7ToDv81ActiveForCurrentPlayback =
                dv7ToDv81SettingActive && dv7ToDv81Probe.supported && !dv7ConversionDisarmedForUrl
            // AUTO fallback: if AUTO chose DV81 but the probe failed for this stream,
            // downgrade to HDR10_BASE_LAYER so the user still gets a picture.
            if (playerSettings.dv7HandlingMode == Dv7HandlingMode.AUTO &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !dv7ToDv81Probe.supported
            ) {
                effectiveDv7Mode = Dv7HandlingMode.HDR10_BASE_LAYER
                Log.i(
                    PlayerRuntimeController.TAG,
                    "DV7_AUTO_FALLBACK: dv81-probe-failed reason=${dv7ToDv81Probe.reason} " +
                            "fallback=HDR10_BASE_LAYER host=${url.safeHost()}"
                )
            }
            hasAttemptedDv7ToDv81ForCurrentPlayback = false
            dv7ToDv81BridgeVersionForCurrentPlayback = dv7ToDv81Probe.bridgeVersion
            dv7ToDv81LastProbeReasonForCurrentPlayback = dv7ToDv81Probe.reason
            Log.i(
                PlayerRuntimeController.TAG,
                "DV7_DOVI: mode=${playerSettings.dv7HandlingMode} " +
                        "effectiveMode=$effectiveDv7Mode " +
                        "dv81Active=$dv7ToDv81SettingActive " +
                        "dv5Compat=${playerSettings.dv5ToDv81Enabled} " +
                        "preserveMapping=${playerSettings.dv7ToDv81PreserveMappingEnabled} " +
                        "buildNative=${DoviBridge.isNativeEnabledInBuild} " +
                        "libraryLoaded=${DoviBridge.isLibraryLoaded} " +
                        "extractorHookReady=${dv7ToDv81Probe.extractorHookReady} " +
                        "active=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                        "reason=${dv7ToDv81Probe.reason} " +
                        "selfTest=${dv7ToDv81Probe.selfTest.reason} " +
                        "bridge=${dv7ToDv81Probe.bridgeVersion ?: "n/a"} " +
                        "host=${url.safeHost()}"
            )

            // ── Diagnostics builder ──
            // Built incrementally during init; written to DataStore on terminal events
            // (first frame rendered = "Played", or final error display = "Error: ...").
            var currentDiagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = url.safeHost(),
                streamUrl = url,
                headersJson = org.json.JSONObject(headers).toString(),
                hdrCapsKnown = dv7AutoResult?.hdrCapsKnown ?: false,
                displayDv = dv7AutoResult?.displayDv ?: false,
                displayHdr10 = dv7AutoResult?.displayHdr10 ?: false,
                displayHdr10Plus = dv7AutoResult?.displayHdr10Plus ?: false,
                codecDv7Supported = dv7AutoResult?.codecSupportsDvheDtb ?: false,
                dv81DecoderName = null,
                bridgeReady = DoviBridge.isLibraryLoaded,
                bridgeVersion = dv7ToDv81Probe.bridgeVersion,
                bridgeReason = dv7ToDv81Probe.reason,
                dv7ModeRequested = playerSettings.dv7HandlingMode.name,
                dv7ModeEffective = effectiveDv7Mode.name,
                dv7AutoDecision = dv7AutoResult?.decision?.name,
                dvSourceProfile = null,
                dv7DoviCalls = 0,
                dv7DoviSuccess = 0,
                dv7DoviSignalRewrites = 0,
                bufferEngineEnabled = false,
                parallelNetworkEnabled = false,
                firstFrameMs = -1L,
                result = "Pending"
            )

            // ── Buffer & Network ──
            // Master toggles off => stock Media3 (DefaultLoadControl, single connection,
            // no cache). DV7 to DV8.1 conversion runs libdovi off-heap, outside the heap
            // budget; a large heap buffer on top of that is what pushed the Fire TV into the
            // lowmemorykiller spiral, so for confirmed DV7 on low-RAM we drop the back buffer
            // and shrink the budget at first frame (below).
            val libdoviConversionActive = effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI
            NuvioExoPlayerPerformanceHelper.updateSettings(playerSettings, context)
            NuvioExoPlayerPerformanceHelper.enabled = playerSettings.nuvioPerformanceModeEnabled
            val streamMime = currentStreamMimeType
            val isHls = streamMime != null && (
                streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                streamMime.lowercase().contains("mpegurl") ||
                streamMime.lowercase().contains("m3u8")
            )
            val rawBandwidthMeter = if (NuvioExoPlayerPerformanceHelper.enabled) {
                NuvioExoPlayerPerformanceHelper.buildBandwidthMeter(context)
            } else {
                DefaultBandwidthMeter.Builder(context)
                    .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
                    .build()
            }
            val bandwidthMeter = SafeBandwidthMeter(rawBandwidthMeter, isHls)
            val loadControl = if (playerSettings.nuvioPerformanceModeEnabled) {
                effectiveBackBufferDurationMs = NuvioExoPlayerPerformanceHelper.backBufferMs
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-native-perf master=on; NuvioExoPlayerPerformanceHelper.buildLoadControl host=${url.safeHost()}"
                )
                NuvioExoPlayerPerformanceHelper.buildLoadControl(context)
            } else if (playerSettings.bufferEngineEnabled) {
                val bufferSettings = playerSettings.bufferSettings
                // Managed (default) caps the buffer at the device budget; off uses Target Buffer Size.
                // Stay full here even on a DV display; first frame tightens only for confirmed DV7.
                val budgetManaged = playerSettings.bufferBudgetManaged
                val budgetMbEffective = if (budgetManaged) {
                    MemoryBudget.budgetMb
                } else {
                    MemoryBudget.effectiveBufferMb(bufferSettings.targetBufferSizeMb)
                        .coerceAtLeast(MemoryBudget.MIN_BUFFER_MB)
                }
                val budgetBytes = budgetMbEffective.toLong() * 1024L * 1024L
                // Build with the user's back buffer so seek-back works immediately (it can't
                // depend on the player re-polling the LoadControl). First frame only lowers it
                // to 0 for confirmed DV7 on low-RAM; everything else keeps it.
                configuredBackBufferMs = bufferSettings.backBufferDurationMs
                val backBufferMsAtBuild = configuredBackBufferMs
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-custom master=on " +
                            "lowRam=${MemoryBudget.isLowRamTier} " +
                            "allowLarge=${playerSettings.allowLargeTargetBuffer} " +
                            "dv7conv=$libdoviConversionActive " +
                            "managed=$budgetManaged " +
                            "backBufferMsAtBuild=$backBufferMsAtBuild (set=$configuredBackBufferMs, lowered to 0 only for real DV7) " +
                            "budgetMb=$budgetMbEffective host=${url.safeHost()}"
                )
                effectiveBackBufferDurationMs = backBufferMsAtBuild
                val allocator = androidx.media3.exoplayer.upstream.DefaultAllocator(
                    true,
                    C.DEFAULT_BUFFER_SEGMENT_SIZE,
                    64,
                    playerSettings.nuvioPerformanceModeEnabled
                )
                BitrateAwareLoadControl(
                    minBufferMs = bufferSettings.minBufferMs,
                    maxBufferMs = bufferSettings.maxBufferMs,
                    bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
                    // Allow buffering past the byte budget until the minimum time threshold is
                    // met. Without this, high-bitrate remux files (e.g. 100+ Mbps UHD MKV with
                    // multiple audio tracks) exhaust the 500MB byte cap in <5s of content before
                    // minBufferMs is satisfied, leaving ExoPlayer stuck in STATE_BUFFERING.
                    prioritizeTimeOverSizeThresholds = true,
                    backBufferDurationMs = backBufferMsAtBuild,
                    retainBackBufferFromKeyframe = true,
                    budgetBytes = budgetBytes,
                    allocator = allocator
                ).also { currentBitrateAwareLoadControl = it }
            } else {
                // Stock LoadControl: DefaultLoadControl configured with 1.5s back buffer so 1s rewind doesn't clear buffer.
                effectiveBackBufferDurationMs = 1_500
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-stock master=off; DefaultLoadControl " +
                            "(1.5s back buffer, no VOD cache) host=${url.safeHost()}"
                )
                DefaultLoadControl.Builder()
                    .setBackBuffer(1_500, /* retainBackBufferFromKeyframe = */ true)
                    .build()
            }
            _loadControl = loadControl

            // VOD cache sits under the buffer master in the UI, so gate it the same way at
            // runtime. The low-RAM + confirmed DV7 case is handled dynamically at first frame
            // (back buffer shrink + budget reduction) rather than blanket-disabling user
            // settings at init, since the stream content isn't known yet at this point.
            val bufferEngineEffective = playerSettings.bufferEngineEnabled
            if (bufferEngineEffective) {
                mediaSourceFactory.vodCacheEnabled = playerSettings.vodCacheEnabled
                mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
                mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
            } else {
                mediaSourceFactory.vodCacheEnabled = false
            }

            if (playerSettings.parallelNetworkEnabled) {
                mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
                mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
                mediaSourceFactory.parallelChunkSizeKb = playerSettings.parallelChunkSizeKb
                mediaSourceFactory.nuvioPerformanceModeEnabled = playerSettings.nuvioPerformanceModeEnabled
            } else {
                // Reset each playback so the factory doesn't keep last stream's state.
                mediaSourceFactory.useParallelConnections = false
                mediaSourceFactory.nuvioPerformanceModeEnabled = false
            }

            // Log the effective state (post-gating), not the raw settings.
            Log.i(
                PlayerRuntimeController.TAG,
                "BUFFER_NETWORK: bufferEngine=${playerSettings.bufferEngineEnabled} " +
                        "parallelNetwork=${playerSettings.parallelNetworkEnabled} " +
                        "useParallel=${mediaSourceFactory.useParallelConnections} " +
                        "vodCache=${mediaSourceFactory.vodCacheEnabled} " +
                        "host=${url.safeHost()}"
            )

            currentDiagnostics = currentDiagnostics.copy(
                bufferEngineEnabled = playerSettings.bufferEngineEnabled,
                parallelNetworkEnabled = playerSettings.parallelNetworkEnabled
            )

            val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive

            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings)
            val awaitedAfr = withTimeoutOrNull(AFR_PREFLIGHT_TOTAL_TIMEOUT_MS) {
                afrJob.await()
            }
            if (awaitedAfr == null) {
                Log.w(PlayerRuntimeController.TAG, "AFR preflight await timed out in ExoPlayer branch; cancelling afrJob")
                afrJob.cancel()
            }

            // ── Libass Setup (From 0.5.7-beta/Left) ──
            requestedUseLibassByUser = playerSettings.useLibass
            val useLibass = when {
                !requestedUseLibassByUser -> false
                libassPipelineOverrideForCurrentStream != null -> libassPipelineOverrideForCurrentStream == true
                else -> true
            }
            val requestedLibassRenderType = playerSettings.libassRenderType.toAssRenderType()
            val libassRenderType = requestedLibassRenderType
            _uiState.update {
                it.copy(
                    useLibass = useLibass,
                    libassRenderType = playerSettings.libassRenderType
                )
            }
            // ── Track Selector Setup ──
            val adaptiveTrackSelectionFactory = AdaptiveTrackSelection.Factory(
                ADAPTIVE_QUALITY_INCREASE_MIN_DURATION_MS,
                AdaptiveTrackSelection.DEFAULT_MAX_DURATION_FOR_QUALITY_DECREASE_MS,
                AdaptiveTrackSelection.DEFAULT_MIN_DURATION_TO_RETAIN_AFTER_DISCARD_MS,
                AdaptiveTrackSelection.DEFAULT_BANDWIDTH_FRACTION
            )
            trackSelector = object : DefaultTrackSelector(context, adaptiveTrackSelectionFactory) {
                override fun selectAllTracks(
                    mappedTrackInfo: MappedTrackInfo,
                    rendererFormatSupports: Array<out Array<out IntArray>>,
                    rendererMixedMimeTypeAdaptationSupports: IntArray,
                    params: Parameters
                ): Array<ExoTrackSelection.Definition?> {
                    val streamMime = currentStreamMimeType
                    val isHls = streamMime != null && (
                        streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                        streamMime.lowercase().contains("mpegurl") ||
                        streamMime.lowercase().contains("m3u8")
                    )
                    Log.d("NuvioTrackSelector", "selectAllTracks run: streamMime=$streamMime, isHls=$isHls")
                    promotePassthroughAudioWhenRendererAlive(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        passthroughPolicy = currentAudioPassthroughPolicy
                    )
                    demoteAudioTunnelingWhereItCannotBeClocked(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        ffmpegRendererName = ffmpegAudioRenderer?.name,
                        audioSink = playbackSpeedAwareAudioSink,
                        deadClockAudioClasses = PlayerTunnelAvSyncPolicy.deadAudioClasses
                    )
                    if (isHls) {
                        for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
                            if (mappedTrackInfo.getRendererType(rendererIndex) == C.TRACK_TYPE_VIDEO) {
                                val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
                                for (groupIndex in 0 until trackGroups.length) {
                                    val group = trackGroups[groupIndex]
                                    for (trackIndex in 0 until group.length) {
                                        val format = group.getFormat(trackIndex)
                                        val support = rendererFormatSupports[rendererIndex][groupIndex][trackIndex]
                                        val formatSupport = RendererCapabilities.getFormatSupport(support)
                                        Log.d("NuvioTrackSelector", "Evaluating track: id=${format.id}, res=${format.width}x${format.height}, mime=${format.sampleMimeType}, codecs=${format.codecs}, support=${formatSupport}")
                                        if (formatSupport == C.FORMAT_EXCEEDS_CAPABILITIES) {
                                            val mime = format.sampleMimeType
                                            val isAvcOrHevc = mime == MimeTypes.VIDEO_H264 || mime == MimeTypes.VIDEO_H265
                                            val isAtMost1080p = format.width <= 1920 && format.height <= 1080
                                            val codecs = format.codecs?.lowercase() ?: ""
                                            val is10Bit = codecs.contains("main10") || codecs.contains("hevc.2") || codecs.contains("hev2")
                                            val isHdr = format.colorInfo?.colorTransfer == C.COLOR_TRANSFER_ST2084
                                            val isStandard8Bit = !is10Bit && !isHdr

                                            Log.d("NuvioTrackSelector", "Conditions for id=${format.id}: isAvcOrHevc=$isAvcOrHevc, isAtMost1080p=$isAtMost1080p, isStandard8Bit=$isStandard8Bit")
                                            if (isAvcOrHevc && isAtMost1080p && isStandard8Bit) {
                                                Log.i("NuvioTrackSelector", "Upgraded track support to FORMAT_HANDLED for id=${format.id}")
                                                rendererFormatSupports[rendererIndex][groupIndex][trackIndex] =
                                                    RendererCapabilities.create(
                                                        C.FORMAT_HANDLED,
                                                        RendererCapabilities.ADAPTIVE_SEAMLESS,
                                                        RendererCapabilities.getTunnelingSupport(support),
                                                        RendererCapabilities.getHardwareAccelerationSupport(support),
                                                        RendererCapabilities.getDecoderSupport(support)
                                                    )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                    return super.selectAllTracks(
                        mappedTrackInfo,
                        rendererFormatSupports,
                        rendererMixedMimeTypeAdaptationSupports,
                        params
                    )
                }
            }.apply {
                setParameters(buildUponParameters().setAllowInvalidateSelectionsOnRendererCapabilitiesChange(true))
                isTunnelingActiveForCurrentPlayback = playerSettings.effectiveTunnelingEnabled &&
                    !safeAudioModeEnabled && !tunnelingDisabledStreamUrls.contains(currentStreamUrl)
                if (isTunnelingActiveForCurrentPlayback) {
                    setParameters(buildUponParameters().setTunnelingEnabled(true))
                } else if (safeAudioModeEnabled) {
                    setParameters(buildUponParameters().setTunnelingEnabled(false).setConstrainAudioChannelCountToDeviceCapabilities(true))
                }
                if (audioDisabledForStream) {
                    setParameters(buildUponParameters().setDisabledTrackTypes(setOf(C.TRACK_TYPE_AUDIO)))
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

                if (preferredAudioLanguages.isNotEmpty()) {
                    setParameters(buildUponParameters().setPreferredAudioLanguages(*preferredAudioLanguages.toTypedArray()))
                }

                val captioningManager = context?.getSystemService(Context.CAPTIONING_SERVICE) as? CaptioningManager
                if (captioningManager != null) {
                    if (!captioningManager.isEnabled) {
                        setParameters(buildUponParameters().setIgnoredTextSelectionFlags(C.SELECTION_FLAG_DEFAULT))
                    }
                    captioningManager.locale?.let { locale ->
                        setParameters(buildUponParameters().setPreferredTextLanguage(locale.isO3Language))
                    }
                }
                // Forced mode: disable ExoPlayer auto text selection; our logic handles it.
                if (playerSettings.subtitleStyle.useForcedSubtitles) {
                    setParameters(buildUponParameters().setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true))
                }
                // When forced subtitles are disabled, tell ExoPlayer to ignore
                // SELECTION_FLAG_FORCED so it won't auto-select forced tracks.
                if (!playerSettings.subtitleStyle.useForcedSubtitles) {
                    val currentFlags = parameters.ignoredTextSelectionFlags
                    setParameters(
                        buildUponParameters().setIgnoredTextSelectionFlags(
                            currentFlags or C.SELECTION_FLAG_FORCED
                        )
                    )
                }
            }

            // ── Extractors & DV Hook ──
            val extractorsFactory = DefaultExtractorsFactory()
                .setTsExtractorFlags(DefaultTsPayloadReaderFactory.FLAG_ENABLE_HDMV_DTS_AUDIO_STREAMS)
                .setTsExtractorTimestampSearchBytes(1500 * TsExtractor.TS_PACKET_SIZE)

            // Manual Convert-to-DV8.1 uses mode 2; if a prior attempt at this stream
            // failed to play, force mode 1 this time (before the HDR10 fallback).
            val dv7Mode1Forced = dv7Mode1ForcedStreamUrls.contains(url)
            val manualDv81Selected = playerSettings.dv7HandlingMode == Dv7HandlingMode.DV81_LIBDOVI
            isManualDv81Mode2ActiveForCurrentPlayback =
                manualDv81Selected &&
                effectiveDv7Mode == Dv7HandlingMode.DV81_LIBDOVI &&
                !libdoviModeOverrideActive &&
                !dv7Mode1Forced
            // DV7 conversion is handled app-side by DolbyVisionExtractorsFactory and the
            // vendored Matroska extractor (wired into effectiveExtractorsFactory below).
            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                dv7ToDv81LastProbeReasonForCurrentPlayback != "ready") {
                dv7ToDv81LastProbeReasonForCurrentPlayback = "app-extractor-factory"
            }

            audioDelayUs.set(_uiState.value.audioDelayMs.toLong() * 1000L)
            subtitleDelayUs.set(_uiState.value.subtitleDelayMs.toLong() * 1000L)

            // ── Fallback Codec Setup ──
            // mapDv7ToHevc is now driven by effective mode (HDR10_BASE_LAYER strips DV7),
            // OR the error handler's per-stream override (preserved for retry-after-failure).
            val mapDv7ToHevcEnabled = effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                    dv7ToHevcForcedStreamUrls.contains(url)
            val isHdr10BaseLayerModeActive = when (playerSettings.dv7HandlingMode) {
                Dv7HandlingMode.AUTO -> dv7AutoResult?.displayDv != true
                else -> effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER ||
                        effectiveDv7Mode == Dv7HandlingMode.STRIP_DV
            }
            com.nuvio.tv.core.player.dvmkv.DolbyVisionCompatibility.setHdr10BaseLayerModeActive(isHdr10BaseLayerModeActive)
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            val convertToDv81Active = !mapDv7ToHevcEnabled &&
                    dv7AutoResult?.decision == DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81
            val codecSelector = createDolbyVisionFallbackCodecSelector(
                convertToDv81Active = convertToDv81Active
            )
            val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
            isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
            // #3287: a policy-denied audio format failed decoder init on an earlier
            // build of this stream; prefer the FFmpeg audio renderer so it decodes.
            val preferFfmpegAudioActive = preferFfmpegAudioStreamUrls.contains(url)
            // Bluetooth media sink (A2DP / LE Audio): Media3 only advertises PCM. Do not attempt
            // optical/HDMI passthrough — decode to PCM and let the BT stack encode SBC/AAC/aptX/LDAC.
            val isBluetoothAudioOutput = currentAudioOutputRoute?.isBluetooth == true ||
                AudioOutputRouteDetector.isBluetoothMediaOutput(context)
            // Force-optical must never win over Bluetooth: AC3/DTS AudioTrack to A2DP fails hard.
            val isForcePassthroughActive = !isBluetoothAudioOutput &&
                playerSettings.forceOpticalPassthrough &&
                playerSettings.decoderPriority != 0
            // Prefer FFmpeg/extension audio decoder on BT so multi-channel TrueHD/DTS always
            // decode to stereo PCM even when the platform MediaCodec path is flaky.
            val effectiveDecoderPriority = if (
                vc1SoftwareFallbackActive ||
                preferFfmpegAudioActive ||
                hasTriedAudioPcmFallback ||
                isForcePassthroughActive ||
                isBluetoothAudioOutput
            ) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                playerSettings.decoderPriority
            }
            // A2DP is stereo; force a clean 2.0 downmix so surround content is audible and balanced.
            val bluetoothStereoDownmix = isBluetoothAudioOutput
            val effectiveDownmixEnabled = playerSettings.effectiveDownmixEnabled || bluetoothStereoDownmix
            val effectiveAudioOutputChannels = if (bluetoothStereoDownmix) {
                com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_0
            } else {
                playerSettings.audioOutputChannels
            }
            if (isBluetoothAudioOutput) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "Bluetooth media output active (route=${currentAudioOutputRoute?.key}): " +
                        "PCM-only sink, stereo downmix, no optical passthrough"
                )
            }

            // ── Surround format resolution (#3287) ──
            // One resolution per player build: which formats may bitstream, how denied
            // formats are handled, and the app-side decode channel target. Bluetooth
            // skips the probe and resolver entirely - the PCM machinery above already
            // owns that route, and the policy stays ALLOW_ALL there.
            val currentRouteKey = currentAudioOutputRoute?.key
            val softwareDecodersAvailable =
                effectiveDecoderPriority != DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF
            val surroundResolution = if (isBluetoothAudioOutput) {
                SurroundFormatResolver.Resolution.INERT
            } else {
                val chainSnapshot = AudioChainProbe.snapshot(context, currentRouteKey)
                val learnedDeniedGroups = AudioRejectionReverifier.ledger.learnedFor(
                    currentRouteKey,
                    playerSettings.audioRejectionsConfirmed
                )
                SurroundFormatResolver.resolve(
                    manualMode = playerSettings.surroundFormatMode == SurroundFormatMode.MANUAL,
                    allowAc3 = playerSettings.allowAc3Passthrough,
                    allowEac3 = playerSettings.allowEac3Passthrough,
                    allowTrueHd = playerSettings.allowTruehdPassthrough,
                    allowDts = playerSettings.allowDtsPassthrough,
                    allowDtsHd = playerSettings.allowDtshdPassthrough,
                    manualTranscodePreferred =
                        playerSettings.deniedCodecHandling == DeniedCodecHandling.TRANSCODE_AC3,
                    manualChannelTargetChannels = when (playerSettings.surroundChannelTarget) {
                        SurroundChannelTarget.AUTO -> null
                        SurroundChannelTarget.CH_2_0 -> 2
                        SurroundChannelTarget.CH_5_1 -> 6
                        SurroundChannelTarget.CH_7_1 -> 8
                    },
                    direct = chainSnapshot.direct,
                    rawMaxPcmChannels = chainSnapshot.maxPcmChannels,
                    routeIsBluetooth = false,
                    routeIsHdmiArc = currentRouteKey != null &&
                        (currentRouteKey.startsWith("type:hdmi_arc") ||
                            currentRouteKey.startsWith("type:hdmi_earc")),
                    softwareDecodersAvailable = softwareDecodersAvailable,
                    forceOpticalActive = isForcePassthroughActive,
                    learnedDeniedGroups = learnedDeniedGroups
                )
            }
            // Tunnel dead-clock memo: re-arm this process from the store, but only for the
            // chain that learned it (firmware, output port and advertised claims must match).
            tunnelDeadClockSignature = if (isBluetoothAudioOutput || currentRouteKey == null) {
                null
            } else {
                val chain = AudioChainProbe.snapshot(context, currentRouteKey)
                PlayerTunnelAvSyncPolicy.chainSignature(
                    fingerprint = Build.FINGERPRINT,
                    routeKey = currentRouteKey,
                    direct = chain.direct,
                    maxPcmChannels = chain.maxPcmChannels,
                )
            }
            tunnelDeadClockSignature?.let { signature ->
                val seeded = PlayerTunnelAvSyncPolicy.seedFromStore(
                    classes = playerSettings.tunnelDeadAudioClasses,
                    storedSignature = playerSettings.tunnelDeadAudioSignature,
                    currentSignature = signature,
                )
                if (seeded.isNotEmpty()) {
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "TUNNEL_AV_SYNC: dead-clock memo restored for $seeded"
                    )
                }
            }
            // Denied formats decode on the app path at the resolved channel target. The
            // user's own downmix target still wins downward: an equal-or-lower layout the
            // user chose is kept; only a higher layout is capped to the resolved target.
            val surroundTargetChannels = surroundResolution.inferredChannelTarget
            val surroundDownmixEnabled = effectiveDownmixEnabled || surroundTargetChannels != null
            val surroundAudioOutputChannels = when {
                surroundTargetChannels == null -> effectiveAudioOutputChannels
                effectiveDownmixEnabled &&
                    effectiveAudioOutputChannels.channelCount <= surroundTargetChannels ->
                    effectiveAudioOutputChannels
                else -> surroundTargetToOutputChannels(surroundTargetChannels, effectiveAudioOutputChannels)
            }
            if (!surroundResolution.policy.allowsEverything() || surroundTargetChannels != null) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "SURROUND_RESOLVE: route=$currentRouteKey policy=[ac3=${surroundResolution.policy.allowAc3} " +
                        "eac3=${surroundResolution.policy.allowEac3} truehd=${surroundResolution.policy.allowTrueHd} " +
                        "dts=${surroundResolution.policy.allowDts} dtshd=${surroundResolution.policy.allowDtsHd} " +
                        "learned=${surroundResolution.policy.learnedDeniedGroups}] " +
                        "transcodePreferred=${surroundResolution.transcodePreferred} " +
                        "channelTarget=$surroundTargetChannels"
                )
                queuePlaybackRawEventLine(
                    "surround_resolve route=$currentRouteKey " +
                        "ac3=${surroundResolution.policy.allowAc3} eac3=${surroundResolution.policy.allowEac3} " +
                        "truehd=${surroundResolution.policy.allowTrueHd} dts=${surroundResolution.policy.allowDts} " +
                        "dtshd=${surroundResolution.policy.allowDtsHd} transcodePreferred=${surroundResolution.transcodePreferred} " +
                        "channelTarget=$surroundTargetChannels"
                )
            }

            // Expose the resolved policy to error recovery (tryDeniedAudioFfmpegFallback).
            currentAudioPassthroughPolicy = surroundResolution.policy

            // Denied formats to re-encode to AC-3 instead of decoding to PCM (stage 3 of
            // #3287). Empty unless the resolver prefers transcode for this chain (Manual:
            // the user's row; Auto: a 2-channel chain that claims AC-3), so nothing changes
            // on a multichannel-PCM chain, on Bluetooth, or under Force AC-3. The renderer
            // still checks that the sink takes AC-3 before it transcodes.
            val deniedTranscodeMimes = DeniedTranscodePlanner.effectiveTranscodeMimes(
                policy = surroundResolution.policy,
                transcodeDeniedToAc3 = surroundResolution.transcodePreferred,
                forcePassthroughActive = isForcePassthroughActive
            )
            if (deniedTranscodeMimes.isNotEmpty()) {
                Log.i(
                    PlayerRuntimeController.TAG,
                    "SURROUND_TRANSCODE: route=$currentRouteKey mimes=$deniedTranscodeMimes"
                )
                queuePlaybackRawEventLine(
                    "surround_transcode route=$currentRouteKey " +
                        "mimes=${deniedTranscodeMimes.joinToString(",")}"
                )
            }

            // ── Renderers Factory (Combining Libass offsets + Audio Gain + Video Fallback) ──
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                audioDelayUsProvider = audioDelayUs::get,
                shouldNormalizeCuePositionProvider = { true },
                shouldStripSdhProvider = {
                    currentPlayerSettingsForReport.subtitleStyle.stripSdh
                },
                isBuiltInSubtitleProvider = {
                    _uiState.value.selectedAddonSubtitle == null
                },
                isSidecarAddonSubtitleActiveProvider = {
                    isSidecarAddonSubtitleActive()
                },
                videoBoundsFractionProvider = {
                    val pv = exoPlayerView
                    if (pv != null) pv.videoBoundsFraction(videoAspectRatio) else null
                },
                gainAudioProcessor = gainAudioProcessor,
                downmixEnabled = surroundDownmixEnabled,
                audioOutputChannels = surroundAudioOutputChannels,
                downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthrough = isForcePassthroughActive,
                deniedTranscodeMimes = deniedTranscodeMimes,
                bluetoothForcePcm = isBluetoothAudioOutput,
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                initialForcePcm = hasTriedAudioPcmFallback || isBluetoothAudioOutput,
                passthroughPolicy = surroundResolution.policy,
                preferSoftwareAudioOnly = (isBluetoothAudioOutput || preferFfmpegAudioActive) && !vc1SoftwareFallbackActive,
                onPlaybackSpeedAwareAudioSinkCreated = { playbackSpeedAwareAudioSink = it },
                onAudioDiagnosticEvent = { line -> queuePlaybackRawEventLine(line) },
                onFfmpegAudioRendererChanged = { renderer ->
                    ffmpegAudioRenderer = renderer
                    renderer?.applyDownmixSettings(
                        downmixEnabled = surroundDownmixEnabled,
                        audioOutputChannels = surroundAudioOutputChannels,
                        downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                        forceOpticalPassthrough = isForcePassthroughActive,
                        deniedTranscodeMimes = deniedTranscodeMimes
                    )
                    applyCenterMixLevel(_uiState.value.centerMixLevelDb)
                    updateAudioControlAvailability()
                }
            ).setExtensionRendererMode(effectiveDecoderPriority)
                .setEnableDecoderFallback(true)
                .setMediaCodecSelector(codecSelector)
                .applyMapDv7ToHevcIfSupported(mapDv7ToHevcEnabled)

            // The app-level factory performs DV7 conversion for the in-band-RPU containers
            // (MP4/fMP4/TS); MKV goes through the vendored extractor. Pass-through for non-DV.
            val stripDvRpuEnabled = playerSettings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV ||
                    effectiveDv7Mode == Dv7HandlingMode.HDR10_BASE_LAYER
            if (stripDvRpuEnabled) {
                Log.i(PlayerRuntimeController.TAG, "DV_RPU_STRIP: enabled — will remove DV RPU NALs")
            }
            val stripHdr10PlusSei = playerSettings.stripHdr10PlusSei
            if (stripHdr10PlusSei) {
                Log.i(PlayerRuntimeController.TAG, "HDR10PLUS_STRIP: enabled — will remove HDR10+ SEI NALs")
            }

            // Always use DolbyVisionExtractorsFactory: when no DV feature is active it
            // still swaps stock Matroska for the vendored extractor (DTS-HD MA / DTS:X
            // first-sample sniff). MP4/TS extractors are returned untouched when
            // config is inactive.
            val effectiveExtractorsFactory: ExtractorsFactory =
                    DolbyVisionExtractorsFactory(
                        delegate = extractorsFactory,
                        config = DolbyVisionConversionConfig(
                            active = isExperimentalDv7ToDv81ActiveForCurrentPlayback,
                            forcedMode = when {
                                libdoviModeOverrideActive -> libdoviModeOverride
                                dv7Mode1Forced -> 1
                                else -> -1
                            },
                            preserveMapping = playerSettings.dv7ToDv81PreserveMappingEnabled &&
                                    manualDv81Selected,
                            dv5Enabled = playerSettings.dv5ToDv81Enabled,
                            manualDv81 = manualDv81Selected && !dv7Mode1Forced
                        ),
                        stripDvRpu = stripDvRpuEnabled,
                        stripHdr10PlusSei = stripHdr10PlusSei
                    ).withNuvioMp4Extractor()

            setLoadingStatus(
                phase = "building_player",
                message = context.getString(R.string.player_loading_building)
            )
            // ── Build ExoPlayer ──
            val buildDefaultPlayer = {
                // The actual MediaSource is built by mediaSourceFactory.createMediaSource()
                // (setMediaSource below), NOT the DefaultMediaSourceFactory on the builder.
                // So the DV7 app-level factory must be wired in here, otherwise
                // createMediaSource falls back to a plain DefaultExtractorsFactory and the
                // conversion never runs. (The libass path wires it via buildWithAssSupportCompat.)
                mediaSourceFactory.configureSubtitleParsing(
                    // Always wire the Matroska factory — DTS-HD sniff must not depend on DV.
                    extractorsFactory = effectiveExtractorsFactory,
                    subtitleParserFactory = null
                )
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setRenderersFactory(renderersFactory)
                    .setLoadControl(loadControl)
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .build()
            }

            disposeExoPlayerBeforeRebuild()
            delay(PLAYER_REBUILD_SETTLE_DELAY_MS)

            _exoPlayer = if (useLibass) {
                val playerDataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(context, headers)
                ExoPlayer.Builder(context)
                    .setBandwidthMeter(bandwidthMeter)
                    .setLoadControl(loadControl)
                    .setTrackSelector(trackSelector!!)
                    .setMediaSourceFactory(DefaultMediaSourceFactory(playerDataSourceFactory, effectiveExtractorsFactory))
                    .setReleaseTimeoutMs(PLAYER_RELEASE_TIMEOUT_MS)
                    .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
                    .buildWithAssSupportCompat(
                        context = context,
                        renderType = libassRenderType,
                        playerMediaSourceFactory = mediaSourceFactory,
                        dataSourceFactory = playerDataSourceFactory,
                        extractorsFactory = effectiveExtractorsFactory,
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
                setPlaybackSpeed(_uiState.value.playbackSpeed)
                if (applyPcmFallbackOnStartup) {
                    pendingAudioPcmFallbackRebuild = false
                    hasTriedAudioPcmFallback = true
                }

                if (playerSettings.skipSilence) skipSilenceEnabled = true
                setHandleAudioBecomingNoisy(true)

                try {
                    currentMediaSession?.release()
                    if (canAdvertiseSession()) {
                        currentMediaSession = MediaSession.Builder(context, SafeMediaSessionPlayer(this)).build()
                    }
                    updateMediaSessionMetadata()
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                applyAudioAmplification(_uiState.value.audioAmplificationDb)
                applyCenterMixLevel(_uiState.value.centerMixLevelDb)

                notifyAudioSessionUpdate(true)

                val preferred = playerSettings.subtitleStyle.preferredLanguage
                val secondary = playerSettings.subtitleStyle.secondaryPreferredLanguage
                applySubtitlePreferences(preferred, secondary)
                applyStartupSubtitlePreparation(startupSubtitlePreparation)
                val startupSubtitleConfigurations = buildStartupSubtitleConfigurations(startupSubtitlePreparation)
                val initialResumePosition = resolvePendingInitialResumePosition()
                playbackAnalyticsDiagnostics.setStartupStartPosition(initialResumePosition)
                playbackAnalyticsDiagnostics.recordRawEventLine(
                    "PLAYER_INIT: engine=EXOPLAYER host=${url.safeHost()} " +
                        "playbackSpeed=${_uiState.value.playbackSpeed} " +
                        "resumePositionMs=$initialResumePosition mime=${currentStreamMimeType ?: "unknown"} " +
                        "bufferEngine=${playerSettings.bufferEngineEnabled} parallel=${mediaSourceFactory.useParallelConnections} " +
                        "vodCache=${mediaSourceFactory.vodCacheEnabled} tunneling=${playerSettings.effectiveTunnelingEnabled}"
                )
                val initialMediaSource = mediaSourceFactory.createMediaSource(
                    context = context,
                    url = url,
                    headers = headers,
                    subtitleConfigurations = startupSubtitleConfigurations,
                    filename = currentFilename,
                    responseHeaders = currentStreamResponseHeaders,
                    mimeTypeOverride = currentStreamMimeType,
                    audioDelayUsProvider = audioDelayUs::get,
                    mediaMetadata = buildMediaSessionMetadata()
                )

                if (initialResumePosition > 0L) {
                    setMediaSource(initialMediaSource, initialResumePosition)
                    clearPendingInitialResumePosition()
                    updatePlaybackTimeline(currentPosition = initialResumePosition)
                } else {
                    setMediaSource(initialMediaSource)
                }

                setLoadingStatus(
                    phase = "starting_stream",
                    message = context.getString(R.string.player_loading_starting)
                )
                val isTunneledPlayback = isTunnelingActiveForCurrentPlayback
                // Hold playWhenReady=false through prepare() so audio does not race ahead
                // while the video decoder is still opening. The first STATE_READY primes the
                // pipeline (ColdStartPrime); synchronized play() begins in onRenderedFirstFrame().
                //
                // Exception: tunneled playback bypasses the normal video rendering pipeline
                // so onRenderedFirstFrame() never fires — TunneledFirstReady starts on READY.
                playWhenReady = false
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (isReleasingPlayer) return
                        logScrobbleDiagnostic(
                            "exo_playback_state",
                            "playbackState=$playbackState playWhenReady=$playWhenReady isPlaying=$isPlaying " +
                                "userPaused=$userPausedManually"
                        )
                        if (playbackState == Player.STATE_READY) {
                            mediaSourceFactory.unlockStartupPrefetch()
                        }
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) { lastKnownDuration = playerDuration }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
                        // Only mark playbackEnded for real finishes so PlayerScreen does not
                        // dispatch next-episode navigation for short debrid/error placeholders.
                        val naturalEnded = playbackState == Player.STATE_ENDED &&
                            shouldTreatAsNaturalPlaybackCompletion(
                                hasRenderedFirstFrame = hasRenderedFirstFrame,
                                hasFatalError = !_uiState.value.error.isNullOrBlank(),
                                durationMs = playerDuration.coerceAtLeast(0L).let { d ->
                                    maxOf(d, lastKnownDuration)
                                }
                            )
                        _uiState.update {
                            it.copy(
                                isBuffering = if (NuvioExoPlayerPerformanceHelper.shouldSuppressBufferingUi(
                                    suppressBufferingUiForSeek, seekBufferingUiDeferred, isBuffering
                                )) false else isBuffering,
                                playbackEnded = naturalEnded
                            )
                        }
                        updateAudioControlAvailability()

                        // Rebuffer telemetry: a rebuffer is STATE_BUFFERING entered
                        // AFTER the first frame (initial startup buffering is excluded).
                        // Accumulate time spent rebuffering; closed out on any non-buffering state.
                        if (playbackState == Player.STATE_BUFFERING) {
                            if (hasRenderedFirstFrame && rebufferStartedAtMs == 0L) {
                                rebufferCount += 1
                                rebufferStartedAtMs = SystemClock.elapsedRealtime()
                                playbackAnalyticsDiagnostics.onRebufferStarted(this@apply, rebufferCount)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "REBUFFER: count=$rebufferCount totalRebufferMs=$rebufferTotalMs " +
                                        "bufferEngine=${currentDiagnostics.bufferEngineEnabled} " +
                                        "dv7dovi=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                                        "host=${currentStreamUrl.safeHost()}"
                                )
                            }
                        } else if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            rebufferTotalMs += lastRebufferMs
                            rebufferStartedAtMs = 0L
                            playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                        }

                        if (isScrubbingModeActive) {
                            isScrubbingModeActive = false
                            _exoPlayer?.setScrubbingModeParameters(
                                ScrubbingModeParameters.Builder().build()
                            )
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering),
                                        detail = "overlay_reopened"
                                    )
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = context.getString(R.string.player_loading_buffering))
                                } else {
                                    recordLoadingDiagnosticEvent(
                                        phase = "buffering_before_first_frame",
                                        message = context.getString(R.string.player_loading_buffering)
                                    )
                                    state.copy(loadingMessage = context.getString(R.string.player_loading_buffering))
                                }
                            }
                        }

                        // Arm stall watchdog while buffering.
                        if (playbackState == Player.STATE_BUFFERING) {
                            maybeScheduleStallWatchdog()
                        } else {
                            cancelStallWatchdog()
                        }

                        if (playbackState == Player.STATE_BUFFERING && pendingSeekTelemetryAwaitingFirstFrame && pendingSeekTelemetryReadyAssumed) {
                            pendingSeekTelemetryReadyAtMs = 0L
                            pendingSeekTelemetryReadyLatencyMs = -1L
                            pendingSeekTelemetryReadyAssumed = false
                        }

                        if (playbackState == Player.STATE_READY) {
                            if (pendingSeekTelemetryRequestedAtMs > 0L && pendingSeekTelemetryReadyAtMs <= 0L) {
                                val latencyMs = (System.currentTimeMillis() - pendingSeekTelemetryRequestedAtMs).coerceAtLeast(0L)
                                pendingSeekTelemetryReadyAtMs = System.currentTimeMillis()
                                pendingSeekTelemetryReadyLatencyMs = latencyMs
                            }
                            // Don't auto-play on the initial STATE_READY — wait
                            // for onRenderedFirstFrame() to ensure A/V sync.
                            // Exception: tunneled playback never fires
                            // onRenderedFirstFrame(), so we must start here.
                            val readyTransition = PlayerStartupPlaybackPolicy.onStateReady(
                                PlayerStartupPlaybackPolicy.ReadyState(
                                    shouldEnforceAutoplayOnFirstReady = shouldEnforceAutoplayOnFirstReady,
                                    hasRenderedFirstFrame = hasRenderedFirstFrame,
                                    userPausedManually = userPausedManually,
                                    startPaused = startPaused,
                                    isTunneledPlayback = isTunneledPlayback,
                                )
                            )
                            shouldEnforceAutoplayOnFirstReady =
                                readyTransition.nextState.shouldEnforceAutoplayOnFirstReady
                            if (readyTransition.nextState.hasRenderedFirstFrame && isTunneledPlayback) {
                                hasRenderedFirstFrame = true
                            }
                            when (val action = readyTransition.action) {
                                is PlayerStartupPlaybackPolicy.ReadyAction.TunneledFirstReady -> {
                                    mediaSourceFactory.unlockStartupPrefetch()
                                    playbackAnalyticsDiagnostics.onSyntheticFirstFrame(this@apply)
                                    if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                        _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                    }
                                    if (action.setPlayWhenReady) {
                                        playWhenReady = true
                                    }
                                    if (action.callPlay) {
                                        play()
                                    }
                                    finishLoadingDiagnostics("first_frame_ready")
                                    currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                                    _uiState.update {
                                        it.copy(
                                            showLoadingOverlay = false,
                                            loadingMessage = null,
                                            loadingProgress = if (it.loadingProgress != null) 1f else null,
                                            showPlayerEngineSwitchInfo = false
                                        )
                                    }
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.ColdStartPrime -> {
                                    if (action.setPlayWhenReady) {
                                        playWhenReady = true
                                    }
                                    if (action.callPlay) {
                                        play()
                                    }
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.PreFirstFrameResume -> {
                                    if (action.setPlayWhenReady) {
                                        playWhenReady = true
                                    }
                                    if (action.callPlay) {
                                        play()
                                    }
                                }
                                is PlayerStartupPlaybackPolicy.ReadyAction.PostFirstFrameResume -> {
                                    if (action.callPlay) {
                                        play()
                                    }
                                }
                                PlayerStartupPlaybackPolicy.ReadyAction.None -> Unit
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                if (NuvioExoPlayerPerformanceHelper.enabled) {
                                    seekBufferingUiDeferred = true
                                    seekBufferingUiJob?.cancel()
                                    seekBufferingUiJob = scope.launch {
                                        delay(seekBufferingUiDelayMs)
                                        seekBufferingUiDeferred = false
                                        if (pendingSeekFlush) {
                                            _uiState.update { it.copy(isBuffering = true) }
                                        }
                                    }
                                }
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()
                            if (!NuvioExoPlayerPerformanceHelper.shouldGuardTrackRebuild() || !hasRenderedFirstFrame) {
                                trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                            }
                            maybeScheduleFirstFrameWatchdog()
                            maybeScheduleTunnelAvSyncWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
                            cancelTunnelAvSyncWatchdog()
                        }

                        if (playbackState == Player.STATE_ENDED) {
                            // Re-persist diagnostics with the final rebuffer totals (the
                            // first-frame snapshot captured 0, since rebuffers accrue after).
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "BUFFER_SUMMARY: rebuffers=$rebufferCount rebufferTotalMs=$rebufferTotalMs " +
                                    "bufferEngine=${currentDiagnostics.bufferEngineEnabled} host=${currentStreamUrl.safeHost()}"
                            )
                            if (currentDiagnostics.result == "Played") {
                                currentDiagnostics = currentDiagnostics.copy(
                                    rebufferCount = rebufferCount,
                                    rebufferTotalMs = rebufferTotalMs
                                )
                                val endDiagnostics = currentDiagnostics
                                lastPlaybackDiagnosticsForReport = endDiagnostics
                                scope.launch {
                                    runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(endDiagnostics) }
                                }
                            }
                            // Marks watched + auto-play next only for real episode finishes;
                            // short debrid/error placeholders are ignored (see #2819).
                            handleNaturalPlaybackEnded()
                        }

                        refreshStableProgressResetGate()
                    }

                    override fun onIsPlayingChanged(isPlaying: Boolean) {
                        logScrobbleDiagnostic(
                            "exo_is_playing_changed",
                            "isPlaying=$isPlaying playbackState=$playbackState playWhenReady=$playWhenReady " +
                                "userPaused=$userPausedManually"
                        )
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
                            if (userPausedManually) schedulePauseOverlay() else cancelPauseOverlay()
                            if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                                stopProgressUpdates()
                            }
                            stopWatchProgressSaving()
                            if (playbackState == Player.STATE_BUFFERING) {
                                saveWatchProgressIfNeeded()
                            } else {
                                when (trackingActionForNonPlayingState(playbackState)) {
                                    TrackingScrobbleAction.PAUSE -> emitPauseScrobbleForCurrentProgress()
                                    TrackingScrobbleAction.STOP -> emitStopScrobbleForCurrentProgress()
                                    TrackingScrobbleAction.START, null -> Unit
                                }
                                saveWatchProgress()
                            }
                        }
                        refreshStableProgressResetGate()
                    }

                    override fun onTracksChanged(tracks: Tracks) {
                        updateAvailableTracks(tracks)
                    }

                    override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
                        if (videoSize.width > 0 && videoSize.height > 0) {
                            currentVideoWidth = videoSize.width
                            currentVideoHeight = videoSize.height
                            Log.d(PlayerRuntimeController.TAG, "onVideoSizeChanged: updated resolution to ${videoSize.width}x${videoSize.height}")
                        }
                    }

                    override fun onRenderedFirstFrame() {
                        val isFirstFrame = !hasRenderedFirstFrame  // capture BEFORE flipping
                        hasRenderedFirstFrame = true
                        mediaSourceFactory.unlockStartupPrefetch()
                        if (isFirstFrame && _uiState.value.postPlayDismissedForCurrentEpisode) {
                            _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                        }
                        updateAudioControlAvailability()
                        // Start playback now that the first video frame is
                        // visible: audio and video begin in sync.
                        if (!startPaused && !userPausedManually) {
                            playWhenReady = true
                            play()
                        }
                        refreshStableProgressResetGate()
                        cancelFirstFrameWatchdog()
                        // The tunnel clock watchdog outlives the first frame: on Amlogic the codec
                        // reports a rendered frame within 50 ms while the tunnel renderer holds it.
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                loadingProgress = if (it.loadingProgress != null) 1f else null,
                                loadingIssueReportVisible = false,
                                loadingIssueElapsedMs = 0L,
                                showPlayerEngineSwitchInfo = false
                            )
                        }
                        finishLoadingDiagnostics("first_frame_rendered")

                        if (isFirstFrame) {
                            currentDiagnostics = recordFirstFrameDiagnostics(this@apply, currentDiagnostics, playerSettings)
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) return
                        cancelFirstFrameWatchdog()
                        cancelTunnelAvSyncWatchdog()
                        val detailedError = error.toDisplayMessage(context)
                        cancelStableProgressReset()

                        // If the codec crashed while the app is in the background (e.g. another
                        // app reclaimed the hardware decoder), don't run the retry chain. Each
                        // retry just re-acquires a decoder the foreground app immediately reclaims
                        // again, burning the retry budget and landing on an unrecoverable
                        // ERROR_CODE_DECODING_FAILED by the time the user returns. Save the
                        // position, free the decoder, and rebuild paused on resume instead.
                        if (isInBackground && isRetryablePlaybackError(error)) {
                            backgroundCrashSavedPositionMs = currentPosition.takeIf { it > 0L } ?: 0L
                            pendingBackgroundCrashRecovery = true
                            errorRetryJob?.cancel()
                            errorRetryJob = scope.launch {
                                releasePlayer(flushPlaybackState = false)
                            }
                            return
                        }

                        // Learn AudioTrack-open rejections (#3287): a chain that advertises a
                        // codec but refuses it at open() is recorded per route and format
                        // group. The record is held until this title's fallback rebuild has
                        // opened an audio track (onAudioTrackInitialized below), so a dead
                        // audio server or a one-off HDMI glitch teaches nothing, and a learned
                        // denial is re-verified by a background open on later launches.
                        // Observation only - the recovery ladder below is unchanged.
                        if (error.errorCode == PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) {
                            val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
                                ?.rendererFormat?.sampleMimeType
                            val rejectedGroup = AudioPassthroughPolicy.groupOf(failingMime)
                            val rejectionRouteKey = currentAudioOutputRoute?.key
                            if (rejectedGroup != null && rejectionRouteKey != null) {
                                AudioRejectionReverifier.ledger.stashPending(
                                    currentStreamUrl,
                                    AudioRejectionLedger.entry(rejectionRouteKey, rejectedGroup)
                                )
                            }
                        }

                        // #3287: decoder init failed on a policy-denied audio format that was
                        // selected under an allowed MIME (hybrid track upgraded mid-stream).
                        // Rebuild with FFmpeg audio preferred so the family decodes.
                        if (tryDeniedAudioFfmpegFallback(error)) {
                            return
                        }

                        // Error handlers: DV codec failures, audio decoder issues, codec state errors.
                        if (error.isDolbyVisionDecoderFailure() && !isMapDv7ToHevcActiveForCurrentPlayback) {
                            // Manual Convert-to-DV8.1 mode 2 failed to decode: try
                            // libdovi mode 1 once before falling back to HDR10.
                            if (isManualDv81Mode2ActiveForCurrentPlayback &&
                                !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
                            ) {
                                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "DV7_MODE2_PLAYBACK_FALLBACK: mode 2 decode failed; " +
                                            "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}"
                                )
                                retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
                                return
                            }
                            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback && !hasAttemptedDv7ToDv81ForCurrentPlayback) {
                                hasAttemptedDv7ToDv81ForCurrentPlayback = true
                                val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
                                dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
                                dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
                            }
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
                        }

                        // DV conversion armed for this stream but the player hit a
                        // FAILED_RUNTIME_CHECK (8000): the converted bitstream trips a
                        // renderer/extractor assertion before the codec ever reports a
                        // decoding failure. That is a video-path failure, so take the same
                        // fallback ladder as a DV decoder failure instead of burning the
                        // audio fallbacks (safe-audio/audio-disabled) on it — they rebuild
                        // the player with the same broken conversion and fail identically.
                        // The stuck-buffering watchdog also arrives as 8000 (ExoPlayerImplInternal
                        // maps its IllegalStateException to FAILED_RUNTIME_CHECK) but it is a load
                        // stall, not a bitstream failure, so it must not drop Dolby Vision.
                        if (error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK &&
                            !error.isStuckBufferingWatchdog() &&
                            (isExperimentalDv7ToDv81ActiveForCurrentPlayback ||
                                isManualDv81Mode2ActiveForCurrentPlayback) &&
                            !isMapDv7ToHevcActiveForCurrentPlayback
                        ) {
                            if (isManualDv81Mode2ActiveForCurrentPlayback &&
                                !dv7Mode1ForcedStreamUrls.contains(currentStreamUrl)
                            ) {
                                dv7Mode1ForcedStreamUrls.add(currentStreamUrl)
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "DV7_MODE2_RUNTIME_CHECK_FALLBACK: mode 2 hit FAILED_RUNTIME_CHECK; " +
                                            "retrying stream at mode 1 host=${currentStreamUrl.safeHost()}"
                                )
                                retryCurrentStreamWithDv7Mode1Fallback(currentPosition)
                                return
                            }
                            if (isExperimentalDv7ToDv81ActiveForCurrentPlayback &&
                                !hasAttemptedDv7ToDv81ForCurrentPlayback
                            ) {
                                hasAttemptedDv7ToDv81ForCurrentPlayback = true
                                val probe = DoviBridge.probeRealtimeConversionSupport(currentStreamUrl)
                                dv7ToDv81LastProbeReasonForCurrentPlayback = probe.reason
                                dv7ToDv81BridgeVersionForCurrentPlayback = probe.bridgeVersion
                            }
                            Log.i(
                                PlayerRuntimeController.TAG,
                                "DV_RUNTIME_CHECK_FALLBACK: FAILED_RUNTIME_CHECK with DV conversion active; " +
                                        "forcing HDR10 base layer for host=${currentStreamUrl.safeHost()}"
                            )
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
                        }

                        if ((error.errorCode == PlaybackException.ERROR_CODE_DECODING_FAILED ||
                             error.errorCode == PlaybackException.ERROR_CODE_FAILED_RUNTIME_CHECK) &&
                            !autoSwitchInternalPlayerOnErrorEnabled) {
                            // Video decoder failures used to consume the safe-audio budget.
                            // Only run this ladder when the failing renderer looks like audio.
                            val failingMime = (error as? androidx.media3.exoplayer.ExoPlaybackException)
                                ?.rendererFormat?.sampleMimeType
                            val audioRendererFailed = failingMime == null ||
                                MimeTypes.isAudio(failingMime)
                            if (audioRendererFailed) {
                                if (!isSafeAudioModeActiveForCurrentPlayback) {
                                    safeAudioForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                    return
                                }
                                if (!hasTriedAudioPcmFallback) {
                                    hasTriedAudioPcmFallback = true
                                    retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                    return
                                }
                                if (!isAudioDisabledForCurrentPlayback) {
                                    audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                    retryCurrentStreamWithAudioDisabled(currentPosition)
                                    return
                                }
                            }
                        }

                        // AudioTrack init (5001) / write (5002): try a light PCM rebuild first,
                        // then the heavier safe-audio / disable-audio path below.
                        if (tryAudioTrackPcmFallback(error)) {
                            return
                        }

                        if (error.isAudioTrackFailure()) {
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!hasTriedAudioPcmFallback) {
                                hasTriedAudioPcmFallback = true
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        if (error.isStuckPlayingNoProgress()) {
                            if (isTunnelingActiveForCurrentPlayback &&
                                !tunnelingDisabledStreamUrls.contains(currentStreamUrl)
                            ) {
                                tunnelingDisabledStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithoutTunneling(currentPosition)
                                return
                            }
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!hasTriedAudioPcmFallback) {
                                hasTriedAudioPcmFallback = true
                                retryCurrentStreamWithSafeAudioFallback(currentPosition)
                                return
                            }
                            if (!isAudioDisabledForCurrentPlayback) {
                                audioDisabledForcedStreamUrls.add(currentStreamUrl)
                                retryCurrentStreamWithAudioDisabled(currentPosition)
                                return
                            }
                        }

                        val timeoutError = error.findCause<SocketTimeoutException>()
                        if (timeoutError != null && timeoutRecoveryAttempts < PlayerRuntimeController.MAX_TIMEOUT_RECOVERY_ATTEMPTS) {
                            retryCurrentStreamAfterTimeout(currentPosition)
                            return
                        }

                        if (error.isUnexpectedLoaderNullPointer() && !hasRetriedCurrentStreamAfterUnexpectedNpe) {
                            hasRetriedCurrentStreamAfterUnexpectedNpe = true
                            retryCurrentStreamAfterUnexpectedNpe(currentPosition)
                            return
                        }

                        if (error.isMediaPeriodHolderStateCrash() && !hasRetriedCurrentStreamAfterMediaPeriodHolderCrash) {
                            hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = true
                            retryCurrentStreamAfterMediaPeriodHolderCrash(currentPosition)
                            return
                        }

                        val responseCode = (error.cause as? androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException)?.responseCode
                        if (responseCode == 416 && !hasRetriedCurrentStreamAfter416) {
                            retryCurrentStreamFromStartAfter416()
                            return
                        }

                        if (tryParsingErrorProbeFallback(
                            error = error,
                            detailedError = detailedError,
                            allowEngineFailover = allowEngineFailover,
                            savedPosition = currentPosition,
                            paused = userPausedManually
                        )) {
                            return
                        }

                        // ── Main Engine Failover ──
                        if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                            return
                        }
                        if (attemptAutoRetry(error, detailedError)) {
                            return
                        }

                        if (rebufferStartedAtMs != 0L) {
                            val lastRebufferMs = (SystemClock.elapsedRealtime() - rebufferStartedAtMs).coerceAtLeast(0L)
                            rebufferTotalMs += lastRebufferMs
                            rebufferStartedAtMs = 0L
                            playbackAnalyticsDiagnostics.onRebufferEnded(this@apply, rebufferTotalMs, lastRebufferMs)
                        }

                        val errorDiagnostics = currentDiagnostics.copy(
                            rebufferCount = rebufferCount,
                            rebufferTotalMs = rebufferTotalMs,
                            result = "Error: $detailedError"
                        )
                        lastPlaybackDiagnosticsForReport = errorDiagnostics
                        lastPlaybackIssueError = PlaybackIssueErrorInput(
                            displayMessage = detailedError,
                            errorCode = error.errorCode,
                            errorCodeName = error.errorCodeName,
                            exceptionClass = error.javaClass.name,
                            causeClass = error.cause?.javaClass?.name,
                            causeMessage = error.cause?.message,
                            httpStatus = error.findInvalidResponseCodeException()?.responseCode
                        )
                        scope.launch {
                            runCatching {
                                playerSettingsDataStore.setLastPlaybackDiagnostics(errorDiagnostics)
                            }
                        }

                        // Fatal error: stop any next-episode auto-play that may have been
                        // armed by a short placeholder ENDED or residual post-play state.
                        cancelNextEpisodeAutoPlayOnFatalError()
                        _uiState.update {
                            it.copy(
                                error = detailedError,
                                showLoadingOverlay = false,
                                showPauseOverlay = false,
                                loadingIssueReportVisible = false,
                                loadingIssueElapsedMs = 0L,
                                playbackEnded = false,
                                postPlayMode = null
                            )
                        }
                    }
                })

                addAnalyticsListener(object : AnalyticsListener {
                    override fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
                        playbackAnalyticsDiagnostics.onPlaybackStateChanged(eventTime, state)
                    }

                    override fun onPlayWhenReadyChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playWhenReady: Boolean,
                        reason: Int
                    ) {
                        playbackAnalyticsDiagnostics.onPlayWhenReadyChanged(eventTime, playWhenReady, reason)
                    }

                    override fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, isPlaying: Boolean) {
                        playbackAnalyticsDiagnostics.onIsPlayingChanged(eventTime, isPlaying)
                    }

                    override fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, isLoading: Boolean) {
                        playbackAnalyticsDiagnostics.onIsLoadingChanged(eventTime, isLoading)
                    }

                    override fun onPlaybackParametersChanged(
                        eventTime: AnalyticsListener.EventTime,
                        playbackParameters: PlaybackParameters
                    ) {
                        playbackAnalyticsDiagnostics.onPlaybackParametersChanged(eventTime, playbackParameters)
                    }

                    override fun onRenderedFirstFrame(
                        eventTime: AnalyticsListener.EventTime,
                        output: Any,
                        renderTimeMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onRenderedFirstFrame(eventTime)
                    }

                    override fun onAudioTrackInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        audioTrackConfig: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig
                    ) {
                        // An audio track opened for this title after a passthrough open was
                        // refused: the audio server is alive, so the refusal was the format.
                        val entry = AudioRejectionReverifier.ledger.takePendingFor(currentStreamUrl) ?: return
                        val routeKey = AudioRejectionLedger.routeOf(entry) ?: return
                        val group = AudioRejectionLedger.groupOf(entry) ?: return
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "AUDIO_REJECTION: ${group.name} refused at open on $routeKey, fallback opened " +
                                "encoding=${audioTrackConfig.encoding}; recording denial"
                        )
                        scope.launch { playerSettingsDataStore.recordAudioRejection(routeKey, group.name) }
                    }

                    override fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
                        playbackAnalyticsDiagnostics.onPlayerError(eventTime, error)
                    }

                    override fun onVideoDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        currentDiagnostics = currentDiagnostics.copy(dv81DecoderName = decoderName)
                        playbackAnalyticsDiagnostics.onVideoDecoderInitialized(
                            eventTime = eventTime,
                            decoderName = decoderName,
                            initializationDurationMs = initializationDurationMs
                        )
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "VIDEO_DECODER: name=$decoderName initMs=$initializationDurationMs host=${currentStreamUrl.safeHost()}"
                        )
                    }

                    override fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                        playbackAnalyticsDiagnostics.onVideoDecoderReleased(eventTime, decoderName)
                    }

                    override fun onVideoInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        playbackAnalyticsDiagnostics.onVideoInputFormatChanged(
                            eventTime = eventTime,
                            format = format,
                            reuseEvaluation = decoderReuseEvaluation
                        )
                    }

                    override fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: androidx.media3.common.VideoSize) {
                        playbackAnalyticsDiagnostics.onVideoSizeChanged(eventTime, videoSize)
                    }

                    override fun onDroppedVideoFrames(
                        eventTime: AnalyticsListener.EventTime,
                        droppedFrames: Int,
                        elapsedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onDroppedVideoFrames(eventTime, droppedFrames, elapsedMs)
                    }

                    override fun onVideoFrameProcessingOffset(
                        eventTime: AnalyticsListener.EventTime,
                        totalProcessingOffsetUs: Long,
                        frameCount: Int
                    ) {
                        playbackAnalyticsDiagnostics.onVideoFrameProcessingOffset(
                            eventTime = eventTime,
                            totalProcessingOffsetUs = totalProcessingOffsetUs,
                            frameCount = frameCount
                        )
                    }

                    override fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, decoderCounters: DecoderCounters) {
                        playbackAnalyticsDiagnostics.onVideoDisabled(eventTime, decoderCounters)
                    }

                    override fun onAudioDecoderInitialized(
                        eventTime: AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioDecoderInitialized(
                            eventTime = eventTime,
                            decoderName = decoderName,
                            initializationDurationMs = initializationDurationMs
                        )
                    }

                    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
                        playbackAnalyticsDiagnostics.onAudioDecoderReleased(eventTime, decoderName)
                    }

                    override fun onAudioInputFormatChanged(
                        eventTime: AnalyticsListener.EventTime,
                        format: Format,
                        decoderReuseEvaluation: DecoderReuseEvaluation?
                    ) {
                        playbackAnalyticsDiagnostics.onAudioInputFormatChanged(
                            eventTime = eventTime,
                            format = format,
                            reuseEvaluation = decoderReuseEvaluation
                        )
                    }

                    override fun onAudioUnderrun(
                        eventTime: AnalyticsListener.EventTime,
                        bufferSize: Int,
                        bufferSizeMs: Long,
                        elapsedSinceLastFeedMs: Long
                    ) {
                        playbackAnalyticsDiagnostics.onAudioUnderrun(
                            eventTime = eventTime,
                            bufferSize = bufferSize,
                            bufferSizeMs = bufferSizeMs,
                            elapsedSinceLastFeedMs = elapsedSinceLastFeedMs
                        )
                        PlayerAudioUnderrunCounter.record()
                    }

                    override fun onBandwidthEstimate(
                        eventTime: AnalyticsListener.EventTime,
                        totalLoadTimeMs: Int,
                        totalBytesLoaded: Long,
                        bitrateEstimate: Long
                    ) {
                        playbackAnalyticsDiagnostics.onBandwidthEstimate(
                            eventTime = eventTime,
                            totalLoadTimeMs = totalLoadTimeMs,
                            totalBytesLoaded = totalBytesLoaded,
                            bitrateEstimate = bitrateEstimate
                        )
                    }

                    override fun onLoadStarted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadStarted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCompleted(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCompleted(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadCanceled(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData
                    ) {
                        playbackAnalyticsDiagnostics.onLoadCanceled(eventTime, loadEventInfo, mediaLoadData)
                    }

                    override fun onLoadError(
                        eventTime: AnalyticsListener.EventTime,
                        loadEventInfo: LoadEventInfo,
                        mediaLoadData: MediaLoadData,
                        error: java.io.IOException,
                        wasCanceled: Boolean
                    ) {
                        playbackAnalyticsDiagnostics.onLoadError(
                            eventTime = eventTime,
                            loadEventInfo = loadEventInfo,
                            mediaLoadData = mediaLoadData,
                            error = error,
                            wasCanceled = wasCanceled
                        )
                    }
                })
            }
            if (!startupSubtitlePreparation.fetchCompleted) {
                fetchAddonSubtitles()
            }
        } catch (e: Exception) {
            if (
                maybeAutoSwitchInternalPlayerOnStartupError(
                    detailedError = e.message ?: context.getString(com.nuvio.tv.R.string.player_error_initialize_failed),
                    allowEngineFailover = allowEngineFailover
                )
            ) {
                return@launch
            }
            val displayError = e.toDisplayMessage(context, context.getString(com.nuvio.tv.R.string.player_error_initialize_failed))
            val diagnostics = LastPlaybackDiagnostics(
                timestampMs = System.currentTimeMillis(),
                host = currentStreamUrl.safeHost(),
                result = "Error: $displayError"
            )
            lastPlaybackDiagnosticsForReport = diagnostics
            lastPlaybackIssueError = PlaybackIssueErrorInput(
                displayMessage = displayError,
                errorCode = null,
                errorCodeName = null,
                exceptionClass = e.javaClass.name,
                causeClass = e.cause?.javaClass?.name,
                causeMessage = e.cause?.message ?: e.message,
                httpStatus = null
            )
            scope.launch {
                runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(diagnostics) }
            }
            _uiState.update {
                it.copy(
                    error = displayError,
                    showLoadingOverlay = false,
                    loadingIssueReportVisible = false,
                    loadingIssueElapsedMs = 0L
                )
            }
        }
    }
}

internal suspend fun PlayerRuntimeController.resolveAutoInternalPlayerEngine(): InternalPlayerEngine {
    val streamMetadataText = buildString {
        currentFilename?.let { appendLine(it) }
        streamName?.let { appendLine(it) }
        currentStreamDescription?.let { appendLine(it) }
        append(title)
    }
    val isHdrOrDv = Regex("""(?i)\b(hdr|hdr10\+?|dv|dolby\s*vision)\b""").containsMatchIn(streamMetadataText)

    return if (isHdrOrDv) {
        InternalPlayerEngine.EXOPLAYER
    } else {
        val hasAnimeId = currentVideoId?.startsWith("kitsu:") == true ||
                currentVideoId?.startsWith("mal:") == true ||
                currentVideoId?.startsWith("anilist:") == true

        if (hasAnimeId) return InternalPlayerEngine.MVP_PLAYER

        metaFetchJob?.let { job ->
            withTimeoutOrNull(3000L) { job.join() }
        }

        val hasAnimeGenre = metaGenres.any { it.equals("anime", ignoreCase = true) }
        val isAnimationFromJapan = (metaGenres.any { it.equals("animation", ignoreCase = true) } &&
                metaCountry?.contains("Japan", ignoreCase = true) == true)

        val isAnime = hasAnimeGenre || isAnimationFromJapan

        if (isAnime) InternalPlayerEngine.MVP_PLAYER else InternalPlayerEngine.EXOPLAYER
    }
}

internal fun resolvePreferredAudioLanguages(
    preferredAudioLanguage: String,
    secondaryPreferredAudioLanguage: String?,
    deviceLanguages: List<String>,
    contentOriginalLanguage: String? = null
): List<String> {
    fun normalize(language: String?): String? {
        val normalized = language?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
        return when (normalized) {
            AudioLanguageOption.DEFAULT,
            AudioLanguageOption.DEVICE,
            SUBTITLE_LANGUAGE_FORCED -> null
            AudioLanguageOption.ORIGINAL -> contentOriginalLanguage?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
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

internal suspend fun PlayerRuntimeController.prepareStartupSubtitles(): StartupSubtitlePreparation {
    return StartupSubtitlePreparation(
        fetchedSubtitles = emptyList(),
        attachedSubtitles = emptyList(),
        fetchCompleted = false
    )
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    isUserExplicitSubtitleSelection = false
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
    attachedAddonSubtitleKeys = emptySet()
    stopSidecarAddonSubtitle(clearView = true)
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
    return prepareStartupSubtitles()
}

internal fun PlayerRuntimeController.applyStartupSubtitlePreparation(startupSubtitlePreparation: StartupSubtitlePreparation) {
    attachedAddonSubtitleKeys = startupSubtitlePreparation.attachedSubtitles.distinctBy { addonSubtitleKey(it) }.map(::addonSubtitleKey).toSet()
    if (!startupSubtitlePreparation.fetchCompleted) return
    _uiState.update { it.copy(addonSubtitles = startupSubtitlePreparation.fetchedSubtitles, isLoadingAddonSubtitles = false, addonSubtitlesError = null) }
}

internal fun PlayerRuntimeController.buildStartupSubtitleConfigurations(startupSubtitlePreparation: StartupSubtitlePreparation): List<androidx.media3.common.MediaItem.SubtitleConfiguration> {
    return startupSubtitlePreparation.attachedSubtitles.distinctBy { "${it.id}|${it.url}" }.map(::toSubtitleConfiguration)
}

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    cancelFirstFrameWatchdog()
    cancelTunnelAvSyncWatchdog()
    cancelStallWatchdog()
    val preparingMessage = context.getString(R.string.player_loading_preparing)
    resetLoadingDiagnostics(
        phase = "preparing",
        message = preparingMessage,
        progress = null
    )
    hasRenderedFirstFrame = false
    hasMarkedCurrentEpisodeCompleted = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
    hasRetriedCurrentStreamAfterMediaPeriodHolderCrash = false
    hasRetriedCurrentStreamAfter416 = false
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
    currentVideoTrackBitrate = -1
    currentVideoTrackColorTransfer = null
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingMessage = preparingMessage,
            loadingIssueReportVisible = false,
            loadingIssueElapsedMs = 0L,
            loadingProgress = null
        )
    }
}

private fun surroundTargetToOutputChannels(
    channels: Int,
    fallback: com.nuvio.tv.data.local.AudioOutputChannels
): com.nuvio.tv.data.local.AudioOutputChannels = when (channels) {
    2 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_0
    3 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_2_1
    4 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_3_1
    6 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_5_1
    8 -> com.nuvio.tv.data.local.AudioOutputChannels.CHANNELS_7_1
    else -> fallback
}

// ── CUSTOM RENDERERS FOR AUDIO/SUBTITLES ──

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val shouldStripSdhProvider: () -> Boolean,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean = { false },
    private val videoBoundsFractionProvider: () -> RectF?,
    private val gainAudioProcessor: GainAudioProcessor,
    private val downmixEnabled: Boolean,
    private val audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    private val downmixNormalizationEnabled: Boolean,
    private val forceOpticalPassthrough: Boolean,
    private val deniedTranscodeMimes: Set<String> = emptySet(),
    private val bluetoothForcePcm: Boolean = false,
    private val playbackSpeedProvider: () -> Float,
    private val initialForcePcm: Boolean = false,
    /**
     * When true, [EXTENSION_RENDERER_MODE_PREFER] applies to audio only — video stays on the
     * platform MediaCodec path so Bluetooth PCM policy does not force software video decode.
     */
    private val preferSoftwareAudioOnly: Boolean = false,
    private val passthroughPolicy: AudioPassthroughPolicy = AudioPassthroughPolicy.ALLOW_ALL,
    private val onPlaybackSpeedAwareAudioSinkCreated: (PlaybackSpeedAwareAudioSink) -> Unit,
    private val onFfmpegAudioRendererChanged: (FfmpegAudioRenderer?) -> Unit,
    private val onAudioDiagnosticEvent: ((String) -> Unit)? = null
) : DefaultRenderersFactory(context) {

    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) {
        val videoExtensionMode = when {
            !preferSoftwareAudioOnly -> extensionRendererMode
            extensionRendererMode == EXTENSION_RENDERER_MODE_PREFER -> EXTENSION_RENDERER_MODE_ON
            else -> extensionRendererMode
        }
        super.buildVideoRenderers(
            context,
            videoExtensionMode,
            mediaCodecSelector,
            enableDecoderFallback,
            eventHandler,
            eventListener,
            allowedVideoJoiningTimeMs,
            out
        )
    }

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // Bluetooth: pin Media3-equivalent DEFAULT (PCM-only) so TV HDMI profiles / force-optical
        // cannot advertise AC3/DTS passthrough while audio is routed to A2DP.
        // Non-BT optical: pin expanded capabilities. Otherwise keep live Builder(context).
        val builder = when {
            bluetoothForcePcm -> {
                DefaultAudioSink.Builder()
                    .setAudioCapabilities(AudioOutputRouteDetector.bluetoothPcmOnlyCapabilities())
            }
            forceOpticalPassthrough -> {
                DefaultAudioSink.Builder(context)
                    .setAudioCapabilities(buildStableAudioCapabilities(context, true))
            }
            else -> DefaultAudioSink.Builder(context)
        }
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
            .setAudioTrackBufferSizeProvider(cappedPassthroughBufferSizeProvider)
        val baseAudioSink = builder.build()
        var speedAwareSink: PlaybackSpeedAwareAudioSink? = null
        val iecAudioSink = IecPassthroughAudioSink(
            sink = baseAudioSink,
            // Bluetooth cannot carry IEC and the outer sink already refuses direct playback
            // there; disabling IEC here also keeps the probe from opening a direct stream.
            hbrIecEnabled = !forceOpticalPassthrough && !bluetoothForcePcm,
            onIecBecameReady = {
                Handler(Looper.getMainLooper()).post {
                    speedAwareSink?.notifyAudioProcessingRequirementChanged()
                }
            },
            onDiagnosticEvent = onAudioDiagnosticEvent
        )
        val playbackSpeedAwareAudioSink = PlaybackSpeedAwareAudioSink(
            sink = iecAudioSink,
            initialForcePcm = initialForcePcm,
            forcePcmForBluetooth = bluetoothForcePcm,
            passthroughPolicy = passthroughPolicy,
            onDiagnosticEvent = onAudioDiagnosticEvent
        )
        speedAwareSink = playbackSpeedAwareAudioSink
        playbackSpeedAwareAudioSink.setInitialPlaybackSpeed(playbackSpeedProvider())
        onPlaybackSpeedAwareAudioSinkCreated(playbackSpeedAwareAudioSink)
        return playbackSpeedAwareAudioSink
    }

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) {
        val playbackAwareSink = audioSink as? PlaybackSpeedAwareAudioSink
        val startIndex = out.size
        super.buildAudioRenderers(
            context,
            extensionRendererMode,
            mediaCodecSelector,
            enableDecoderFallback,
            audioSink,
            eventHandler,
            eventListener,
            out
        )
        if (playbackAwareSink != null && out.size > startIndex) {
            val mediaCodecAudioRendererIndex = (startIndex until out.size)
                .firstOrNull { index -> out[index] is MediaCodecAudioRenderer }
                ?: startIndex
            out[mediaCodecAudioRendererIndex] =
                PlaybackSpeedAwareAudioRenderer(
                    rendererContext = context,
                    codecAdapterFactory = getCodecAdapterFactory(),
                    mediaCodecSelector = mediaCodecSelector,
                    enableDecoderFallback = enableDecoderFallback,
                    eventHandler = eventHandler,
                    eventListener = eventListener,
                    playbackSpeedAwareAudioSink = playbackAwareSink
                )
        }
        applyFfmpegRendererSettings(out)
    }

    override fun buildTextRenderers(
        context: Context,
        output: TextOutput,
        outputLooper: android.os.Looper,
        extensionRendererMode: Int,
        out: ArrayList<Renderer>
    ) {
        val normalizingOutput = CueNormalizingTextOutput(
            delegate = SdhFilteringTextOutput(output, shouldStripSdhProvider),
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider,
            isBuiltInSubtitleProvider = isBuiltInSubtitleProvider,
            isSidecarAddonSubtitleActiveProvider = isSidecarAddonSubtitleActiveProvider,
            videoBoundsFractionProvider = videoBoundsFractionProvider
        )
        val startIndex = out.size
        super.buildTextRenderers(context, normalizingOutput, outputLooper, extensionRendererMode, out)
        for (index in startIndex until out.size) {
            out[index] = SubtitleOffsetRenderer(
                baseRenderer = out[index],
                subtitleDelayUsProvider = subtitleDelayUsProvider,
                audioDelayUsProvider = audioDelayUsProvider
            )
        }
    }

    private fun applyFfmpegRendererSettings(out: ArrayList<Renderer>) {
        val ffmpegRenderers = out.filterIsInstance<FfmpegAudioRenderer>()
        ffmpegRenderers.forEach { renderer ->
            renderer.applyDownmixSettings(
                downmixEnabled = downmixEnabled,
                audioOutputChannels = audioOutputChannels,
                downmixNormalizationEnabled = downmixNormalizationEnabled,
                forceOpticalPassthrough = forceOpticalPassthrough,
                deniedTranscodeMimes = deniedTranscodeMimes
            )
        }
        onFfmpegAudioRendererChanged(ffmpegRenderers.firstOrNull())
    }
}
private fun FfmpegAudioRenderer.applyDownmixSettings(
    downmixEnabled: Boolean,
    audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    downmixNormalizationEnabled: Boolean,
    forceOpticalPassthrough: Boolean,
    deniedTranscodeMimes: Set<String>
) {
    setForceOpticalPassthrough(forceOpticalPassthrough)
    setDeniedTranscodeMimes(deniedTranscodeMimes)
    if (downmixEnabled) {
        setAudioOutputChannels(
            audioOutputChannels.ffmpegLayoutName,
            audioOutputChannels.channelCount
        )
        setDownmixNormalizationEnabled(downmixNormalizationEnabled)
    } else {
        setAudioOutputChannels(null, 0)
        setDownmixNormalizationEnabled(false)
    }
}

private class CueNormalizingTextOutput(
    private val delegate: TextOutput,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val isBuiltInSubtitleProvider: () -> Boolean,
    private val isSidecarAddonSubtitleActiveProvider: () -> Boolean,
    private val videoBoundsFractionProvider: () -> RectF?
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        val cues = cueGroup.cues
        if (cues.isEmpty()) {
            delegate.onCues(cueGroup)
            return
        }
        var modifiedList: ArrayList<Cue>? = null
        val count = cues.size
        for (i in 0 until count) {
            val original = cues[i]
            val processed = processCue(original)
            if (processed !== original) {
                if (modifiedList == null) {
                    modifiedList = ArrayList(count)
                    for (j in 0 until i) {
                        modifiedList.add(cues[j])
                    }
                }
                modifiedList.add(processed)
            } else {
                modifiedList?.add(original)
            }
        }
        val processedCues = modifiedList ?: cues
        val mergedCues = PlayerSubtitleUtils.mergeOverlappingCues(processedCues)
        delegate.onCues(CueGroup(mergedCues, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        if (isSidecarAddonSubtitleActiveProvider()) {
            return
        }
        if (cues.isEmpty()) {
            delegate.onCues(cues)
            return
        }
        var modifiedList: ArrayList<Cue>? = null
        val count = cues.size
        for (i in 0 until count) {
            val original = cues[i]
            val processed = processCue(original)
            if (processed !== original) {
                if (modifiedList == null) {
                    modifiedList = ArrayList(count)
                    for (j in 0 until i) {
                        modifiedList.add(cues[j])
                    }
                }
                modifiedList.add(processed)
            } else {
                modifiedList?.add(original)
            }
        }
        val processedCues = modifiedList ?: cues
        val mergedCues = PlayerSubtitleUtils.mergeOverlappingCues(processedCues)
        delegate.onCues(mergedCues)
    }

    private fun processCue(cue: Cue): Cue {
        var processed = SubtitleMojibakeSanitizer.sanitizeCue(cue)
        processed = PlayerSubtitleRtlFix.fixCueText(processed, isBuiltInSubtitleProvider())
        if (shouldNormalizeCuePositionProvider()) {
            processed = normalizeCuePosition(processed)
        }
        if (processed.bitmap != null) {
            val bounds = videoBoundsFractionProvider()
            if (bounds != null && bounds.width() > 0f && bounds.height() > 0f) {
                val isIdentity = bounds.left == 0f && bounds.top == 0f
                    && bounds.width() == 1f && bounds.height() == 1f
                if (!isIdentity) {
                    processed = remapBitmapCueToVideoBounds(processed, bounds)
                }
            }
        }
        return processed
    }

    private fun remapBitmapCueToVideoBounds(cue: Cue, bounds: RectF): Cue {
        val builder = cue.buildUpon()
        if (cue.position != Cue.DIMEN_UNSET) {
            builder.setPosition(bounds.left + cue.position * bounds.width())
        }
        if (cue.size != Cue.DIMEN_UNSET) {
            builder.setSize(cue.size * bounds.width())
        }
        if (cue.lineType == Cue.LINE_TYPE_FRACTION && cue.line != Cue.DIMEN_UNSET) {
            builder.setLine(bounds.top + cue.line * bounds.height(), Cue.LINE_TYPE_FRACTION)
        }
        if (cue.bitmapHeight != Cue.DIMEN_UNSET) {
            builder.setBitmapHeight(cue.bitmapHeight * bounds.height())
        }
        return builder.build()
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

private class SubtitleOffsetRenderer(
    private val baseRenderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long
) : ForwardingRenderer(baseRenderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val subtitleOffsetUs = subtitleDelayUsProvider()
        val audioOffsetUs = audioDelayUsProvider()
        val adjustedPositionUs = (positionUs + audioOffsetUs - subtitleOffsetUs).coerceAtLeast(0L)
        
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
    return details.contains("dolby-vision", ignoreCase = true) && details.contains("decoder failed", ignoreCase = true)
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
            (details.contains("nullpointerexception", ignoreCase = true) && details.contains("matroskaextractor", ignoreCase = true))
}

private fun PlaybackException.isAudioTrackFailure(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isAudioTrackFailure(errorCode, details)
}

private fun PlaybackException.isStuckBufferingWatchdog(): Boolean {
    val details = buildString {
        append(message ?: "")
        append(' ')
        append(cause?.message ?: "")
        append(' ')
        append(cause?.cause?.message ?: "")
    }
    return isStuckBufferingWatchdog(errorCode, details)
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
    return details.contains("mediaperiodholder", ignoreCase = true) && details.contains(".info", ignoreCase = true) && details.contains("null", ignoreCase = true)
}

private fun String.safeHost(): String {
    return runCatching { Uri.parse(this).host ?: "unknown" }.getOrDefault("unknown")
}

/**
 * Parses the DV profile number from a codec string, e.g. "dvhe.07.06" gives 7.
 * Used as a fallback when libdovi bridge hasn't loaded (e.g. HDR10_BASE_LAYER
 * mode strips DV before the bridge runs, so its source-profile detector
 * never sees the stream).
 */
private fun parseDvProfileFromCodecString(codecs: String?): Int? {
    if (codecs.isNullOrBlank()) return null
    val match = Regex("^(?:dvhe|dvav|dvh1|dva1)\\.(\\d+)\\.").find(codecs.trim().lowercase()) ?: return null
    return match.groupValues[1].toIntOrNull()
}

/** Human-friendly codec name for the diagnostics card. */
private fun friendlyVideoCodecName(mimeType: String?, codecs: String?): String? {
    val mime = mimeType?.lowercase()
    return when {
        mime == null -> null
        mime == MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        mime == MimeTypes.VIDEO_H265 -> "HEVC"
        mime == MimeTypes.VIDEO_H264 -> "H.264"
        mime == MimeTypes.VIDEO_AV1 -> "AV1"
        mime == MimeTypes.VIDEO_VP9 -> "VP9"
        mime.startsWith("video/") -> mime.removePrefix("video/").uppercase()
        else -> codecs ?: mime
    }
}

/**
 * Human-friendly HDR/output type for the diagnostics card — reflects what is
 * actually output, not just the source track mime. When DV7 is stripped to the
 * HDR10 base layer the output is HDR10/SDR even though the track mime is DV.
 */
private fun friendlyVideoHdrType(
    mimeType: String?,
    colorTransfer: Int?,
    effectiveModeName: String?,
    dvConversionOccurred: Boolean
): String? {
    val isDolbyVisionMime = mimeType?.lowercase() == MimeTypes.VIDEO_DOLBY_VISION
    fun fromTransfer(): String? = when (colorTransfer) {
        C.COLOR_TRANSFER_ST2084 -> "HDR10"
        C.COLOR_TRANSFER_HLG -> "HLG"
        C.COLOR_TRANSFER_SDR -> "SDR"
        else -> null
    }
    return when {
        // Ignore DV data: output is HDR10/SDR, never Dolby Vision.
        effectiveModeName == "HDR10_BASE_LAYER" -> fromTransfer() ?: "HDR10"
        // DV RPU stripped: output is HDR10 base layer, never Dolby Vision.
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: "HDR10"
        // DV8.1 conversion, but only label it DV if a conversion actually ran. AUTO arms
        // this mode for every file on a DV display, so plain SDR/HDR10 lands here too.
        effectiveModeName == "DV81_LIBDOVI" && dvConversionOccurred -> "Dolby Vision"
        effectiveModeName == "DV81_LIBDOVI" -> fromTransfer()
        // Native DV passthrough.
        isDolbyVisionMime -> "Dolby Vision"
        else -> fromTransfer()
    }
}

private fun createDolbyVisionFallbackCodecSelector(
    convertToDv81Active: Boolean = false
): MediaCodecSelector {
    // Stripping DV7 to its HEVC base layer is handled by the renderer (setMapDV7ToHevc),
    // which only touches profile 7. We must NOT force video/dolby-vision to the HEVC
    // decoder here: that also catches DV5, which has no HDR10 base layer and ends up
    // decoded without its reshaping (wrong colors). DV5 keeps the DV decoder.
    if (convertToDv81Active) {
        return MediaCodecSelector { mimeType, requiresSecureDecoder, requiresTunnelingDecoder ->
            val defaults = MediaCodecSelector.DEFAULT.getDecoderInfos(
                mimeType, requiresSecureDecoder, requiresTunnelingDecoder
            )
            if (mimeType != MimeTypes.VIDEO_DOLBY_VISION || defaults.isNotEmpty()) {
                return@MediaCodecSelector defaults
            }
            DolbyVisionCodecFallback.findDvDecodersIgnoringProfile()
        }
    }
    return MediaCodecSelector.DEFAULT
}

private fun describeExtensionRendererMode(mode: Int): String {
    return when (mode) {
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF -> "off"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON -> "on"
        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER -> "prefer"
        else -> mode.toString()
    }
}

private fun DefaultRenderersFactory.applyMapDv7ToHevcIfSupported(enabled: Boolean): DefaultRenderersFactory {
    return runCatching {
        val method = javaClass.getMethod("setMapDV7ToHevc", Boolean::class.javaPrimitiveType)
        method.invoke(this, enabled)
        this
    }.getOrElse { this }
}

/**
 * HDMI encodings often arrive after the first AudioTrack, so the selector starts
 * on PCM/AAC and reselects AC-3 / DTS / TrueHD seconds later. If the audio
 * renderer can handle anything, treat every bitstream passthrough mime as HANDLED
 * so the first selection is the passthrough track.
 */
private fun promotePassthroughAudioWhenRendererAlive(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    passthroughPolicy: AudioPassthroughPolicy?
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        var rendererAlive = false
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                if (RendererCapabilities.getFormatSupport(supports[groupIndex][trackIndex]) ==
                    C.FORMAT_HANDLED
                ) {
                    rendererAlive = true
                }
            }
        }
        if (!rendererAlive) continue
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val mime = group.getFormat(trackIndex).sampleMimeType ?: continue
                if (!isPassthroughAudioMime(mime)) continue
                // #3287: never promote a format the surround-format policy denies onto the device renderer.
                if (passthroughPolicy?.deniesPassthrough(mime) == true) continue
                val current = supports[groupIndex][trackIndex]
                if (RendererCapabilities.getFormatSupport(current) == C.FORMAT_HANDLED) continue
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    C.FORMAT_HANDLED,
                    RendererCapabilities.ADAPTIVE_SEAMLESS,
                    RendererCapabilities.getTunnelingSupport(current),
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

// Tunnelled video releases frames against the platform's hw_av_sync audio clock, so the
// selected audio track must be one the HAL will clock. Clear the tunnelling capability of
// tracks that will not be: HBR formats the IEC sink would carry on its own track (no HAL
// has been seen to clock an app-packed IEC 61937 stream) and tracks of an audio class
// already seen with a dead tunnel clock on this chain. Whether a HAL clocks software PCM
// under tunnelling is a device fact: some never start that clock, while some TVs only
// render 4K video through the tunnel, so FFmpeg-decoded tracks keep their tunnel until the
// dead-clock watchdog has seen the PCM class fail on this chain and memoised it. With the
// audio side cleared, DefaultTrackSelector leaves both renderers untunnelled and the video
// plays through the normal pipeline instead of holding every frame.
private fun demoteAudioTunnelingWhereItCannotBeClocked(
    mappedTrackInfo: androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo,
    rendererFormatSupports: Array<out Array<out IntArray>>,
    ffmpegRendererName: String?,
    audioSink: PlaybackSpeedAwareAudioSink?,
    deadClockAudioClasses: Set<String>
) {
    for (rendererIndex in 0 until mappedTrackInfo.rendererCount) {
        if (mappedTrackInfo.getRendererType(rendererIndex) != C.TRACK_TYPE_AUDIO) continue
        val rendererName = mappedTrackInfo.getRendererName(rendererIndex)
        val isFfmpegRenderer = ffmpegRendererName != null && rendererName == ffmpegRendererName
        val trackGroups = mappedTrackInfo.getTrackGroups(rendererIndex)
        val supports = rendererFormatSupports[rendererIndex]
        for (groupIndex in 0 until trackGroups.length) {
            val group = trackGroups[groupIndex]
            for (trackIndex in 0 until group.length) {
                val format = group.getFormat(trackIndex)
                val reason = when {
                    // The FFmpeg renderer hands the sink PCM whatever the bitstream is, so its
                    // tracks belong to the PCM class regardless of what the sink could pass through.
                    isFfmpegRenderer ->
                        if (deadClockAudioClasses.contains(PlaybackSpeedAwareAudioSink.TUNNEL_AUDIO_CLASS_PCM)) {
                            "dead-tunnel-clock"
                        } else {
                            null
                        }
                    audioSink == null -> null
                    audioSink.demandsNonTunnelledVideo(format) -> "iec-hbr"
                    deadClockAudioClasses.isNotEmpty() &&
                        deadClockAudioClasses.contains(audioSink.tunnelAudioClass(format)) -> "dead-tunnel-clock"
                    else -> null
                } ?: continue
                val current = supports[groupIndex][trackIndex]
                if (RendererCapabilities.getTunnelingSupport(current) ==
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED
                ) {
                    continue
                }
                Log.d(
                    "NuvioTrackSelector",
                    "Tunnelling cleared for renderer=$rendererName mime=${format.sampleMimeType} reason=$reason"
                )
                supports[groupIndex][trackIndex] = RendererCapabilities.create(
                    RendererCapabilities.getFormatSupport(current),
                    RendererCapabilities.getAdaptiveSupport(current),
                    RendererCapabilities.TUNNELING_NOT_SUPPORTED,
                    RendererCapabilities.getHardwareAccelerationSupport(current),
                    RendererCapabilities.getDecoderSupport(current)
                )
            }
        }
    }
}

private fun isPassthroughAudioMime(mime: String): Boolean {
    return mime == MimeTypes.AUDIO_AC3 ||
        mime == MimeTypes.AUDIO_E_AC3 ||
        mime == MimeTypes.AUDIO_E_AC3_JOC ||
        mime == MimeTypes.AUDIO_AC4 ||
        mime == MimeTypes.AUDIO_DTS ||
        mime == MimeTypes.AUDIO_DTS_HD ||
        mime == MimeTypes.AUDIO_DTS_EXPRESS ||
        mime == MimeTypes.AUDIO_DTS_X ||
        mime == MimeTypes.AUDIO_TRUEHD ||
        mime.startsWith("audio/vnd.dts")
}

private val defaultPassthroughBuffers = DefaultAudioTrackBufferSizeProvider.Builder().build()

private val cappedPassthroughBufferSizeProvider =
    DefaultAudioSink.AudioTrackBufferSizeProvider { minBuffer, encoding, outputMode, pcmFrameSize, sampleRate, bitrate, maxSpeed ->
        val size = defaultPassthroughBuffers.getBufferSizeInBytes(
            minBuffer,
            encoding,
            outputMode,
            pcmFrameSize,
            sampleRate,
            bitrate,
            maxSpeed
        )
        val capBytes = if (outputMode == DefaultAudioSink.OUTPUT_MODE_PASSTHROUGH) {
            passthroughBufferCapBytes(encoding)
        } else {
            Int.MAX_VALUE
        }
        maxOf(minBuffer, minOf(size, capBytes))
    }

/** Per-codec RAW AudioTrack buffer cap in bytes. */
private fun passthroughBufferCapBytes(encoding: Int): Int {
    return when (encoding) {
        C.ENCODING_DOLBY_TRUEHD -> 2 * 61_440
        C.ENCODING_DTS_HD, C.ENCODING_DTS_UHD_P2 -> 4 * 30_720
        C.ENCODING_DTS -> 16 * 2_012
        C.ENCODING_E_AC3, C.ENCODING_E_AC3_JOC -> 2 * 10_752
        C.ENCODING_AC3 -> 1_536 * 8
        C.ENCODING_AC4 -> 16_384
        else -> 16_384
    }
    }

private fun buildStableAudioCapabilities(context: Context, forceOpticalPassthrough: Boolean = false): AudioCapabilities {
    val detected = AudioCapabilities.getCapabilities(context, AudioAttributes.DEFAULT, null)
    val supportedEncodings = mutableListOf<Int>()
    val knownEncodings = intArrayOf(
        C.ENCODING_PCM_16BIT, C.ENCODING_AC3, C.ENCODING_AC4, C.ENCODING_DTS,
        C.ENCODING_E_AC3_JOC, C.ENCODING_E_AC3, C.ENCODING_DOLBY_TRUEHD
    )
    for (encoding in knownEncodings) {
        if (detected.supportsEncoding(encoding)) {
            supportedEncodings += encoding
        }
    }
    if ((detected.supportsEncoding(C.ENCODING_DTS_HD) || detected.supportsEncoding(C.ENCODING_DTS_UHD_P2)) && C.ENCODING_DTS !in supportedEncodings) {
        supportedEncodings += C.ENCODING_DTS
    }
    if (forceOpticalPassthrough) {
        val forced = intArrayOf(
            C.ENCODING_AC3,
            C.ENCODING_E_AC3,
            C.ENCODING_E_AC3_JOC,
            C.ENCODING_DTS,
            C.ENCODING_DTS_HD
        )
        for (encoding in forced) {
            if (encoding !in supportedEncodings) {
                supportedEncodings += encoding
            }
        }
    }
    val maxChannelCount = if (forceOpticalPassthrough) {
        maxOf(detected.maxChannelCount, 8)
    } else {
        detected.maxChannelCount
    }
    return AudioCapabilities(supportedEncodings.toIntArray(), maxChannelCount)
}

private class SafeBandwidthMeter(
    private val delegate: BandwidthMeter,
    private val isHls: Boolean
) : BandwidthMeter {
    override fun getBitrateEstimate(): Long {
        val raw = delegate.bitrateEstimate
        return if (isHls) maxOf(raw, 25_000_000L) else raw
    }

    override fun getTimeToFirstByteEstimateUs(): Long = delegate.timeToFirstByteEstimateUs

    override fun getTransferListener(): androidx.media3.datasource.TransferListener? = delegate.transferListener

    override fun addEventListener(
        eventHandler: android.os.Handler,
        eventListener: BandwidthMeter.EventListener
    ) {
        delegate.addEventListener(eventHandler, eventListener)
    }

    override fun removeEventListener(eventListener: BandwidthMeter.EventListener) {
        delegate.removeEventListener(eventListener)
    }
}

private fun PlayerRuntimeController.recordFirstFrameDiagnostics(
    player: ExoPlayer,
    currentDiagnostics: LastPlaybackDiagnostics,
    playerSettings: com.nuvio.tv.data.local.PlayerSettings
): LastPlaybackDiagnostics {
    val startupMs = (System.currentTimeMillis() - playerInitializationStartedAtMs).coerceAtLeast(0L)
    val conversionCalls = DoviBridge.getConversionCallCount()
    val conversionSucceeded = DoviBridge.getConversionSuccessCount()
    val signalingRewrites = DolbyVisionConversionStats.getCodecStringRewriteCount()
    val sourceProfile = DolbyVisionConversionStats.getLastSourceProfile()
        ?: parseDvProfileFromCodecString(currentVideoTrackCodecs)
    val conversionMode = DolbyVisionConversionStats.getLastSelectedConversionMode()
    val conversionAttempted = hasAttemptedDv7ToDv81ForCurrentPlayback || conversionCalls > 0 || signalingRewrites > 0
    if (pendingSeekTelemetryAwaitingFirstFrame && pendingSeekTelemetryRequestedAtMs > 0L) {
        pendingSeekTelemetryRequestedAtMs = 0L
        pendingSeekTelemetryTargetMs = -1L
        pendingSeekTelemetryReadyAtMs = 0L
        pendingSeekTelemetryReadyLatencyMs = -1L
        pendingSeekTelemetryAwaitingFirstFrame = false
    }

    val clickToFirstFrameMs = launchStartedAtElapsedMs
        ?.let { (SystemClock.elapsedRealtime() - it).coerceAtLeast(0L) }
        ?: -1L
    val playbackSnapshot = playbackAnalyticsDiagnostics.snapshot(
        player = player,
        hasRenderedFirstFrame = true,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        rebufferStartedAtMs = rebufferStartedAtMs
    )
    playbackAnalyticsDiagnostics.recordRawEventLine(
        "PLAYBACK_STARTUP: clickToFirstFrameMs=$clickToFirstFrameMs " +
            "initToFirstFrameMs=$startupMs playbackSpeed=${player.playbackParameters.speed} " +
            "pitch=${player.playbackParameters.pitch} startPositionMs=${player.currentPosition.coerceAtLeast(0L)} " +
            "currentPositionMs=${player.currentPosition.coerceAtLeast(0L)} bufferedMs=${player.bufferedPosition.coerceAtLeast(0L)} " +
            "durationMs=${player.duration.takeIf { it > 0L } ?: -1L} " +
            "video=${playbackSnapshot.videoFormat?.sampleMimeType ?: currentVideoTrackMimeType ?: "n/a"} " +
            "codecs=${playbackSnapshot.videoFormat?.codecs ?: currentVideoTrackCodecs ?: "n/a"} " +
            "size=${playbackSnapshot.videoFormat?.width ?: currentVideoTrackWidth}x${playbackSnapshot.videoFormat?.height ?: currentVideoTrackHeight} " +
            "frameRate=${playbackSnapshot.videoFormat?.frameRate ?: -1f} " +
            "bitrate=${playbackSnapshot.videoFormat?.bitrate ?: -1} " +
            "bandwidthBps=${playbackSnapshot.bandwidthEstimateBps ?: -1L} " +
            "loads=${playbackSnapshot.loadCompletedCount}/${playbackSnapshot.loadStartedCount} " +
            "bytesLoaded=${playbackSnapshot.totalBytesLoaded} droppedFrames=${playbackSnapshot.droppedFrames} " +
            "audioUnderruns=${playbackSnapshot.audioUnderrunCount} rebufferCount=$rebufferCount " +
            "host=${currentStreamUrl.safeHost()} engine=$currentInternalPlayerEngine"
    )

    val dvConversionOccurred = conversionSucceeded > 0 ||
        signalingRewrites > 0 ||
        sourceProfile != null

    currentBitrateAwareLoadControl?.let { lc ->
        val budgetManaged = playerSettings.bufferBudgetManaged
        val keepZeroForDv7 = budgetManaged && conversionSucceeded > 0L &&
                MemoryBudget.isLowRamTier
        val resolvedBackBufferMs = if (keepZeroForDv7) 0 else configuredBackBufferMs
        if (resolvedBackBufferMs != effectiveBackBufferDurationMs) {
            lc.setBackBufferDurationOverrideMs(resolvedBackBufferMs)
            effectiveBackBufferDurationMs = resolvedBackBufferMs
        }
        if (keepZeroForDv7) {
            lc.setBudgetBytesOverride(
                MemoryBudget.conversionBudgetMb.toLong() * 1024L * 1024L
            )
        }
        Log.i(
            PlayerRuntimeController.TAG,
            "BACK_BUFFER_RESOLVED: dvConversion=$dvConversionOccurred " +
                    "lowRam=${MemoryBudget.isLowRamTier} " +
                    "resolvedBackBufferMs=$resolvedBackBufferMs " +
                    "managed=$budgetManaged " +
                    "budgetMb=${when {
                        keepZeroForDv7 -> MemoryBudget.conversionBudgetMb
                        budgetManaged -> MemoryBudget.budgetMb
                        else -> MemoryBudget.effectiveBufferMb(playerSettings.bufferSettings.targetBufferSizeMb)
                    }} " +
                    "host=${currentStreamUrl.safeHost()}"
        )
    }
    val finalDiagnostics = currentDiagnostics.copy(
        firstFrameMs = startupMs,
        dv7DoviCalls = conversionCalls.toInt(),
        dv7DoviSuccess = conversionSucceeded.toInt(),
        dv7DoviSignalRewrites = signalingRewrites.toInt(),
        dvSourceProfile = sourceProfile?.toString(),
        videoResolution = if (currentVideoTrackWidth > 0 && currentVideoTrackHeight > 0)
            "${currentVideoTrackWidth}x${currentVideoTrackHeight}" else null,
        videoCodec = friendlyVideoCodecName(currentVideoTrackMimeType, currentVideoTrackCodecs),
        videoHdrType = friendlyVideoHdrType(
            currentVideoTrackMimeType,
            currentVideoTrackColorTransfer,
            currentDiagnostics.dv7ModeEffective,
            dvConversionOccurred
        ),
        videoBitrate = run {
            val durationMsVal = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
            val sizeBytes = currentVideoSize
            if (sizeBytes != null && sizeBytes > 0L && durationMsVal > 0L) {
                val durationSecs = durationMsVal / 1000.0
                ((sizeBytes * 8.0) / durationSecs).toInt()
            } else {
                currentVideoTrackBitrate
            }
        },
        durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
        rebufferCount = rebufferCount,
        rebufferTotalMs = rebufferTotalMs,
        result = "Played"
    )
    lastPlaybackDiagnosticsForReport = finalDiagnostics
    scope.launch {
        runCatching {
            playerSettingsDataStore.setLastPlaybackDiagnostics(finalDiagnostics)
        }
    }
    return finalDiagnostics
}

@androidx.annotation.OptIn(UnstableApi::class)
private fun PlayerView.videoBoundsFraction(aspectRatio: Float): RectF? {
    val subtitleView = this.subtitleView ?: return null
    val viewWidth = subtitleView.width.toFloat()
    val viewHeight = subtitleView.height.toFloat()
    if (viewWidth <= 0f || viewHeight <= 0f) return null

    if (aspectRatio > 0f) {
        val parentRatio = viewWidth / viewHeight
        return if (parentRatio > aspectRatio) {
            val fitW = viewHeight * aspectRatio
            val leftPx = (viewWidth - fitW) / 2f
            RectF(leftPx / viewWidth, 0f, (leftPx + fitW) / viewWidth, 1f)
        } else {
            val fitH = viewWidth / aspectRatio
            val topPx = (viewHeight - fitH) / 2f
            RectF(0f, topPx / viewHeight, 1f, (topPx + fitH) / viewHeight)
        }
    }

    val contentFrame = getTag(androidx.media3.ui.R.id.exo_content_frame) as? AspectRatioFrameLayout
        ?: findViewById<AspectRatioFrameLayout>(androidx.media3.ui.R.id.exo_content_frame)
            ?.also { setTag(androidx.media3.ui.R.id.exo_content_frame, it) }
        ?: return null
    val frameWidth = contentFrame.width.toFloat()
    val frameHeight = contentFrame.height.toFloat()
    if (frameWidth <= 0f || frameHeight <= 0f) return null
    if (frameWidth > viewWidth || frameHeight > viewHeight) return null
    val left = contentFrame.x / viewWidth
    val top = contentFrame.y / viewHeight
    return RectF(
        left,
        top,
        left + frameWidth / viewWidth,
        top + frameHeight / viewHeight,
    )
}
