package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.content.res.Resources
import android.media.audiofx.LoudnessEnhancer
import android.net.Uri
import android.os.Build
import android.util.Log
import android.view.accessibility.CaptioningManager
import android.media.MediaFormat
import android.os.Handler
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
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.MediaCodecAudioRenderer
import androidx.media3.exoplayer.mediacodec.MediaCodecAdapter
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.RendererConfiguration
import androidx.media3.exoplayer.trackselection.AdaptiveTrackSelection
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.trackselection.MappingTrackSelector.MappedTrackInfo
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.exoplayer.upstream.BandwidthMeter
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nuvio.tv.R
import com.nuvio.tv.core.player.DolbyVisionCodecFallback
import com.nuvio.tv.core.player.DolbyVisionBaseLayerPolicy
import com.nuvio.tv.core.player.BitrateAwareLoadControl
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionConversionStats
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import com.nuvio.tv.core.player.DoviBridge
import com.nuvio.tv.core.player.LastPlaybackDiagnostics
import com.nuvio.tv.ui.screens.settings.MemoryBudget
import com.nuvio.tv.data.local.AddonSubtitleStartupMode
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.Dv7HandlingMode
import com.nuvio.tv.data.local.FrameRateMatchingMode
import com.nuvio.tv.data.local.SUBTITLE_LANGUAGE_FORCED
import com.nuvio.tv.data.local.InternalPlayerEngine
import com.nuvio.tv.data.local.PlayerSettings
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

private const val STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS = 20_000L
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

    scope.launch {
        try {
            if (allowEngineFailover) {
                startupEngineFailoverTriggered = false
            }
            autoSubtitleSelected = false
            hasScannedTextTracksOnce = false
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
            rememberAudioDelayPerDeviceEnabled = playerSettings.rememberAudioDelayPerDevice
            if (rememberAudioDelayPerDeviceEnabled) {
                registerAudioDelayRouteCallback()
                applyStoredAudioDelayForCurrentRouteIfEnabled()
            }
            cachedDecoderPriority = playerSettings.decoderPriority
            val preferredAudioLanguages = resolvePreferredAudioLanguages(
                preferredAudioLanguage = playerSettings.preferredAudioLanguage,
                secondaryPreferredAudioLanguage = playerSettings.secondaryPreferredAudioLanguage,
                deviceLanguages = resolveDeviceAudioLanguages(),
                contentOriginalLanguage = contentLanguage
            )
            mpvPreferredAudioLanguages = preferredAudioLanguages
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
            val deviceAspectMode = deviceLocalPlayerPreferences.aspectMode.first()
            _uiState.update {
                it.copy(
                    internalPlayerEngine = effectiveInternalPlayerEngine,
                    frameRateMatchingMode = playerSettings.frameRateMatchingMode,
                    resizeMode = playerSettings.resizeMode,
                    aspectMode = deviceAspectMode,
                    tunnelingEnabled = playerSettings.tunnelingEnabled &&
                            effectiveInternalPlayerEngine != InternalPlayerEngine.MVP_PLAYER,
                    loadingMessage = context.getString(R.string.player_loading_detecting_format)
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
                        Log.d(PlayerRuntimeController.TAG, "AFR display mode switched; delaying MPV start by ${MPV_AFR_SETTLE_DELAY_MS}ms")
                        delay(MPV_AFR_SETTLE_DELAY_MS)
                    }
                    _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_buffering)) }
                    initializeMpvPlayer(url = url, headers = headers, allowEngineFailover = allowEngineFailover)
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
            isExperimentalDv7ToDv81ActiveForCurrentPlayback = dv7ToDv81SettingActive && dv7ToDv81Probe.supported
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
            val loadControl = if (playerSettings.bufferEngineEnabled) {
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
                BitrateAwareLoadControl(
                    minBufferMs = bufferSettings.minBufferMs,
                    maxBufferMs = bufferSettings.maxBufferMs,
                    bufferForPlaybackMs = bufferSettings.bufferForPlaybackMs,
                    bufferForPlaybackAfterRebufferMs = bufferSettings.bufferForPlaybackAfterRebufferMs,
                    prioritizeTimeOverSizeThresholds = false,
                    backBufferDurationMs = backBufferMsAtBuild,
                    // Retain back to the keyframe before the boundary, else a backward seek
                    // into the buffer has no keyframe to decode from and re-fetches. The
                    // persisted setting defaults false and isn't exposed, so force it on.
                    retainBackBufferFromKeyframe = true,
                    budgetBytes = budgetBytes
                ).also { currentBitrateAwareLoadControl = it }
            } else {
                // Stock LoadControl: DefaultLoadControl's back buffer is 0 by default.
                effectiveBackBufferDurationMs = 0
                currentBitrateAwareLoadControl = null
                Log.i(
                    PlayerRuntimeController.TAG,
                    "BUFFER_GATE: engine=exo-stock master=off; DefaultLoadControl " +
                            "(no back buffer, no VOD cache) host=${url.safeHost()}"
                )
                DefaultLoadControl.Builder().build()
            }

            // VOD cache sits under the buffer master in the UI, so gate it the same way at
            // runtime (and off during conversion). Set explicitly every time so a stale
            // value can't leave it running once the master is off.
            val bufferEngineEffective = playerSettings.bufferEngineEnabled && !libdoviConversionActive
            if (bufferEngineEffective) {
                mediaSourceFactory.vodCacheEnabled = playerSettings.vodCacheEnabled
                mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
                mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb
            } else {
                mediaSourceFactory.vodCacheEnabled = false
            }

            if (playerSettings.parallelNetworkEnabled && !libdoviConversionActive) {
                mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
                mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
                mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
            } else {
                // Reset each playback so the factory doesn't keep last stream's state; also
                // off during conversion to avoid piling network buffers on the native cost.
                mediaSourceFactory.useParallelConnections = false
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
                bufferEngineEnabled = playerSettings.bufferEngineEnabled && !libdoviConversionActive,
                parallelNetworkEnabled = playerSettings.parallelNetworkEnabled && !libdoviConversionActive
            )

            val safeAudioModeEnabled = safeAudioForcedStreamUrls.contains(url)
            val audioDisabledForStream = audioDisabledForcedStreamUrls.contains(url)
            val vc1TrackSelectionBypassActive = vc1TrackSelectionBypassStreamUrls.contains(url)
            isSafeAudioModeActiveForCurrentPlayback = safeAudioModeEnabled
            isAudioDisabledForCurrentPlayback = audioDisabledForStream
            isVc1TrackSelectionBypassActiveForCurrentPlayback = vc1TrackSelectionBypassActive

            val startupSubtitlePreparation = prepareStreamStartSubtitles(playerSettings)
            afrJob.await()

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
                if (playerSettings.tunnelingEnabled && !safeAudioModeEnabled) {
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
            //   DolbyVisionCompatibility.setMapDv7ToHevcEnabled(mapDv7ToHevcEnabled)
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            val convertToDv81Active = !mapDv7ToHevcEnabled &&
                    dv7AutoResult?.decision == DolbyVisionBaseLayerPolicy.Decision.CONVERT_TO_DV81
            val codecSelector = createDolbyVisionFallbackCodecSelector(
                convertToDv81Active = convertToDv81Active
            )
            val vc1SoftwareFallbackActive = vc1SoftwarePreferredStreamUrls.contains(url)
            isVc1SoftwareFallbackActiveForCurrentPlayback = vc1SoftwareFallbackActive
            val isForcePassthroughActive = playerSettings.forceOpticalPassthrough && playerSettings.decoderPriority != 0
            val effectiveDecoderPriority = if (vc1SoftwareFallbackActive || hasTriedAudioPcmFallback || isForcePassthroughActive) {
                DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
            } else {
                playerSettings.decoderPriority
            }

            // ── Renderers Factory (Combining Libass offsets + Audio Gain + Video Fallback) ──
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get,
                audioDelayUsProvider = audioDelayUs::get,
                shouldNormalizeCuePositionProvider = {
                    val selectedAddonSubtitle = _uiState.value.selectedAddonSubtitle
                    selectedAddonSubtitle != null && PlayerSubtitleUtils.mimeTypeFromUrl(selectedAddonSubtitle.url) == MimeTypes.TEXT_VTT
                },
                gainAudioProcessor = gainAudioProcessor,
                downmixEnabled = playerSettings.downmixEnabled,
                audioOutputChannels = playerSettings.audioOutputChannels,
                downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                forceOpticalPassthrough = isForcePassthroughActive,
                playbackSpeedProvider = { _uiState.value.playbackSpeed },
                initialForcePcm = hasTriedAudioPcmFallback,
                onPlaybackSpeedAwareAudioSinkCreated = { playbackSpeedAwareAudioSink = it },
                onFfmpegAudioRendererChanged = { renderer ->
                    ffmpegAudioRenderer = renderer
                    renderer?.applyDownmixSettings(
                        downmixEnabled = playerSettings.downmixEnabled,
                        audioOutputChannels = playerSettings.audioOutputChannels,
                        downmixNormalizationEnabled = !playerSettings.maintainOriginalAudioOnDownmix,
                        forceOpticalPassthrough = isForcePassthroughActive
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
            val stripDvRpuEnabled = playerSettings.dv7HandlingMode == Dv7HandlingMode.STRIP_DV
            if (stripDvRpuEnabled) {
                Log.i(PlayerRuntimeController.TAG, "DV_RPU_STRIP: enabled — will remove DV RPU NALs")
            }

            val effectiveExtractorsFactory: ExtractorsFactory =
                if (isExperimentalDv7ToDv81ActiveForCurrentPlayback || stripDvRpuEnabled) {
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
                        stripDvRpu = stripDvRpuEnabled
                    )
                } else {
                    extractorsFactory
                }

            val streamMime = currentStreamMimeType
            val isHls = streamMime != null && (
                streamMime.equals(MimeTypes.APPLICATION_M3U8, ignoreCase = true) ||
                streamMime.lowercase().contains("mpegurl") ||
                streamMime.lowercase().contains("m3u8")
            )
            val rawBandwidthMeter = DefaultBandwidthMeter.Builder(context)
                .setInitialBitrateEstimate(ADAPTIVE_INITIAL_BITRATE_ESTIMATE_BPS)
                .build()
            val bandwidthMeter = SafeBandwidthMeter(rawBandwidthMeter, isHls)

            _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_building)) }
            // ── Build ExoPlayer ──
            val buildDefaultPlayer = {
                // The actual MediaSource is built by mediaSourceFactory.createMediaSource()
                // (setMediaSource below), NOT the DefaultMediaSourceFactory on the builder.
                // So the DV7 app-level factory must be wired in here, otherwise
                // createMediaSource falls back to a plain DefaultExtractorsFactory and the
                // conversion never runs. (The libass path wires it via buildWithAssSupportCompat.)
                mediaSourceFactory.configureSubtitleParsing(
                    extractorsFactory =
                        if (isExperimentalDv7ToDv81ActiveForCurrentPlayback || stripDvRpuEnabled) effectiveExtractorsFactory else null,
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
                        currentMediaSession = MediaSession.Builder(context, this).build()
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

                setMediaSource(
                    mediaSourceFactory.createMediaSource(
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
                )

                _uiState.update { it.copy(loadingMessage = context.getString(R.string.player_loading_starting)) }
                val isTunneledPlayback = playerSettings.tunnelingEnabled
                // Always start paused — playback begins in onRenderedFirstFrame()
                // so audio and video start in perfect sync. Without this, the
                // audio renderer races ahead by 1-2s while the video decoder
                // is still decoding the first I-frame.
                //
                // Exception: tunneled playback bypasses the normal video
                // rendering pipeline so onRenderedFirstFrame() never fires.
                // In that case we fall back to starting on STATE_READY.
                playWhenReady = false
                prepare()

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (isReleasingPlayer) return
                        val playerDuration = duration
                        if (playerDuration > lastKnownDuration) { lastKnownDuration = playerDuration }
                        val isBuffering = playbackState == Player.STATE_BUFFERING
                        updatePlaybackTimeline(duration = playerDuration.coerceAtLeast(0L))
                        _uiState.update {
                            it.copy(
                                isBuffering = isBuffering,
                                playbackEnded = playbackState == Player.STATE_ENDED
                            )
                        }
                        updateAudioControlAvailability()

                        // Rebuffer telemetry: a rebuffer is STATE_BUFFERING entered
                        // AFTER the first frame (initial startup buffering is excluded).
                        // Accumulate time spent rebuffering; closed out on any non-buffering state.
                        if (playbackState == Player.STATE_BUFFERING) {
                            if (hasRenderedFirstFrame && rebufferStartedAtMs == 0L) {
                                rebufferCount += 1
                                rebufferStartedAtMs = System.currentTimeMillis()
                                Log.i(
                                    PlayerRuntimeController.TAG,
                                    "REBUFFER: count=$rebufferCount totalRebufferMs=$rebufferTotalMs " +
                                        "bufferEngine=${currentDiagnostics.bufferEngineEnabled} " +
                                        "dv7dovi=${isExperimentalDv7ToDv81ActiveForCurrentPlayback} " +
                                        "host=${currentStreamUrl.safeHost()}"
                                )
                            }
                        } else if (rebufferStartedAtMs != 0L) {
                            rebufferTotalMs += (System.currentTimeMillis() - rebufferStartedAtMs).coerceAtLeast(0L)
                            rebufferStartedAtMs = 0L
                        }

                        if (playbackState == Player.STATE_BUFFERING && !hasRenderedFirstFrame) {
                            _uiState.update { state ->
                                if (state.loadingOverlayEnabled && !state.showLoadingOverlay) {
                                    state.copy(showLoadingOverlay = true, showControls = false, loadingMessage = context.getString(R.string.player_loading_buffering))
                                } else {
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
                            if (shouldEnforceAutoplayOnFirstReady) {
                                shouldEnforceAutoplayOnFirstReady = false
                                if (isTunneledPlayback) {
                                    // Tunneled mode — onRenderedFirstFrame() won't
                                    // fire; treat STATE_READY as the sync point.
                                    hasRenderedFirstFrame = true
                                    if (_uiState.value.postPlayDismissedForCurrentEpisode) {
                                        _uiState.update { it.copy(postPlayDismissedForCurrentEpisode = false) }
                                    }
                                    if (!startPaused && !userPausedManually) {
                                        playWhenReady = true
                                        play()
                                    }
                                    _uiState.update {
                                        it.copy(
                                            showLoadingOverlay = false,
                                            loadingMessage = null,
                                            loadingProgress = if (it.loadingProgress != null) 1f else null,
                                            showPlayerEngineSwitchInfo = false
                                        )
                                    }
                                }
                                // Non-tunneled: playback will start in onRenderedFirstFrame().
                            } else if (!userPausedManually && hasRenderedFirstFrame) {
                                play()
                            }
                            tryApplyPendingResumeProgress(this@apply)
                            _uiState.value.pendingSeekPosition?.let { position ->
                                seekTo(position)
                                _uiState.update { it.copy(pendingSeekPosition = null) }
                            }
                            tryAutoSelectPreferredSubtitleFromAvailableTracks()
                            trackSelectionParameters = trackSelectionParameters.buildUpon().build()
                            maybeScheduleFirstFrameWatchdog()
                        } else if (playbackState == Player.STATE_ENDED || playbackState == Player.STATE_IDLE) {
                            cancelFirstFrameWatchdog()
                        }

                        if (playbackState == Player.STATE_ENDED) {
                            // emitCompletionScrobbleStop(progressPercent = 99.5f)
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
                                scope.launch {
                                    runCatching { playerSettingsDataStore.setLastPlaybackDiagnostics(endDiagnostics) }
                                }
                            }
                            saveWatchProgress()
                            resetPostPlayStateAfterPlaybackEnded()
                        }

                        refreshStableProgressResetGate()
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
                            if (userPausedManually) schedulePauseOverlay() else cancelPauseOverlay()
                            stopProgressUpdates()
                            stopWatchProgressSaving()
                            if (playbackState == Player.STATE_BUFFERING) {
                                saveWatchProgressIfNeeded()
                            } else {
                                emitStopScrobbleForCurrentProgress()
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
                        _uiState.update {
                            it.copy(
                                showLoadingOverlay = false,
                                loadingMessage = null,
                                loadingProgress = if (it.loadingProgress != null) 1f else null,
                                showPlayerEngineSwitchInfo = false
                            )
                        }

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
                        if (isFirstFrame) {
                            Log.i(PlayerRuntimeController.TAG, "PLAYBACK_STARTUP: firstFrameMs=$startupMs dv7doviActive=$isExperimentalDv7ToDv81ActiveForCurrentPlayback dv7doviAttempted=$conversionAttempted dvSourceProfile=${sourceProfile ?: "n/a"} dvConvertMode=${conversionMode ?: "n/a"} dv7doviSignalRewrites=$signalingRewrites dv7doviCalls=$conversionCalls dv7doviSuccess=$conversionSucceeded dv7doviReason=${dv7ToDv81LastProbeReasonForCurrentPlayback ?: "n/a"} dv7doviBridge=${dv7ToDv81BridgeVersionForCurrentPlayback ?: "n/a"} dv7hevcActive=$isMapDv7ToHevcActiveForCurrentPlayback host=${currentStreamUrl.safeHost()}")

                            // Real DV7 only if a conversion actually succeeded (or a DV profile
                            // / codec rewrite was seen). Don't use conversionCalls: the startup
                            // self-test fires one failing call on every playback, so calls is
                            // always >= 1. Drives the "Dolby Vision" label and the DV7 memory cap.
                            val dvConversionOccurred = conversionSucceeded > 0 ||
                                signalingRewrites > 0 ||
                                sourceProfile != null

                            // Now that we know if it's really DV7: keep the back buffer at 0 for
                            // DV7 on low-RAM (off-heap conversion memory), otherwise hand back the
                            // user's value (covers non-DV content that merely armed conversion).
                            currentBitrateAwareLoadControl?.let { lc ->
                                // Signal-only DV5 runs no libdovi conversion (sourceProfile 5 with
                                // no successful calls), so it needs no off-heap headroom. Only real
                                // RPU conversion (DV7, or forced-mode DV5) warrants the cap.
                                // Cap only when libdovi actually converted (off-heap memory); stripped
                                // DV7 / native DV / signal-only DV5 leave success at 0. Off trusts the user.
                                val budgetManaged = playerSettings.bufferBudgetManaged
                                val keepZeroForDv7 = budgetManaged && conversionSucceeded > 0L &&
                                        MemoryBudget.isLowRamTier
                                val resolvedBackBufferMs = if (keepZeroForDv7) 0 else configuredBackBufferMs
                                if (resolvedBackBufferMs != effectiveBackBufferDurationMs) {
                                    lc.setBackBufferDurationOverrideMs(resolvedBackBufferMs)
                                    effectiveBackBufferDurationMs = resolvedBackBufferMs
                                }
                                // DV7 on low-RAM: also drop to the conversion budget (takes
                                // effect on the next track reselection) for off-heap headroom.
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
                                rebufferCount = rebufferCount,
                                rebufferTotalMs = rebufferTotalMs,
                                result = "Played"
                            )
                            // Keep currentDiagnostics in sync so the playback-end
                            // re-persist (below) captures the final rebuffer totals.
                            currentDiagnostics = finalDiagnostics
                            scope.launch {
                                runCatching {
                                    playerSettingsDataStore.setLastPlaybackDiagnostics(finalDiagnostics)
                                }
                            }
                        }
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        if (isReleasingPlayer && error.errorCode == PlaybackException.ERROR_CODE_TIMEOUT) return
                        cancelFirstFrameWatchdog()
                        val detailedError = buildString {
                            append(error.message ?: context.getString(R.string.player_error_playback_fallback))
                            val cause = error.cause
                            if (cause is androidx.media3.datasource.HttpDataSource.InvalidResponseCodeException) {
                                append(" (HTTP ${cause.responseCode})")
                            } else if (cause != null) append(": ${cause.message}")
                            append(" [${error.errorCode}]")
                        }
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

                        if (error.isAudioTrackInitializationFailure()) {
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
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
                            if (!isSafeAudioModeActiveForCurrentPlayback) {
                                safeAudioForcedStreamUrls.add(currentStreamUrl)
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

                        // ── Main Engine Failover ──
                        if (maybeAutoSwitchInternalPlayerOnStartupError(detailedError = detailedError, allowEngineFailover = allowEngineFailover)) {
                            return
                        }
                        if (attemptAutoRetry(error, detailedError)) {
                            return
                        }

                        val errorDiagnostics = currentDiagnostics.copy(
                            result = "Error: $detailedError"
                        )
                        scope.launch {
                            runCatching {
                                playerSettingsDataStore.setLastPlaybackDiagnostics(errorDiagnostics)
                            }
                        }

                        _uiState.update { it.copy(error = detailedError, showLoadingOverlay = false, showPauseOverlay = false) }
                    }
                })

                addAnalyticsListener(object : androidx.media3.exoplayer.analytics.AnalyticsListener {
                    override fun onVideoDecoderInitialized(
                        eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                        decoderName: String,
                        initializedTimestampMs: Long,
                        initializationDurationMs: Long
                    ) {
                        currentDiagnostics = currentDiagnostics.copy(dv81DecoderName = decoderName)
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "VIDEO_DECODER: name=$decoderName initMs=$initializationDurationMs host=${currentStreamUrl.safeHost()}"
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
            _uiState.update {
                it.copy(
                    error = e.toDisplayMessage(context, context.getString(com.nuvio.tv.R.string.player_error_initialize_failed)),
                    showLoadingOverlay = false
                )
            }
        }
    }
}

internal fun PlayerRuntimeController.resolveAutoInternalPlayerEngine(): InternalPlayerEngine {
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
        val hasAnimeGenre = metaGenres.any { it.equals("anime", ignoreCase = true) }
        val isAnimationFromJapan = (metaGenres.any { it.equals("animation", ignoreCase = true) } &&
                metaCountry?.contains("Japan", ignoreCase = true) == true)
        val hasAnimeId = currentVideoId?.startsWith("kitsu:") == true ||
                currentVideoId?.startsWith("mal:") == true ||
                currentVideoId?.startsWith("anilist:") == true

        val isAnime = hasAnimeGenre || hasAnimeId || isAnimationFromJapan

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
    showOnlyPreferredLanguages: Boolean = false
): StartupSubtitlePreparation {
    val effectiveMode = if (showOnlyPreferredLanguages && mode == AddonSubtitleStartupMode.ALL_SUBTITLES) {
        AddonSubtitleStartupMode.PREFERRED_ONLY
    } else {
        mode
    }

    if (effectiveMode == AddonSubtitleStartupMode.FAST_STARTUP) {
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
        "none" -> listOfNotNull(secondaryLanguage?.takeIf { it.isNotBlank() })
        else -> listOfNotNull(preferredLanguage, secondaryLanguage?.takeIf { it.isNotBlank() })
    }.map { PlayerSubtitleUtils.normalizeLanguageCode(it) }.distinct()

    if (effectiveMode == AddonSubtitleStartupMode.PREFERRED_ONLY && preferredTargets.isEmpty()) {
        return StartupSubtitlePreparation(
            fetchedSubtitles = emptyList(),
            attachedSubtitles = emptyList(),
            fetchCompleted = false
        )
    }

    _uiState.update { it.copy(isLoadingAddonSubtitles = true, addonSubtitlesError = null) }

    val fetchedSubtitles = withTimeoutOrNull(STARTUP_SUBTITLE_PREFETCH_TIMEOUT_MS) {
        fetchAddonSubtitlesNow(
            onProgress = { completed, total, addonName ->
                val msg = if (completed == 0) {
                    context.getString(R.string.player_loading_subtitles_from, total)
                } else if (addonName != null) {
                    context.getString(R.string.player_loading_subtitles_addon, addonName, completed, total)
                } else {
                    context.getString(R.string.player_loading_subtitles_progress, completed, total)
                }
                _uiState.update { it.copy(loadingMessage = msg) }
            }
        )
    } ?: return StartupSubtitlePreparation(emptyList(), emptyList(), false)

    val attachedSubtitles = when (effectiveMode) {
        AddonSubtitleStartupMode.ALL_SUBTITLES -> fetchedSubtitles
        AddonSubtitleStartupMode.PREFERRED_ONLY -> fetchedSubtitles.filter { subtitle -> preferredTargets.any { target -> PlayerSubtitleUtils.matchesLanguageCode(subtitle.lang, target) } }
        AddonSubtitleStartupMode.FAST_STARTUP -> emptyList()
    }

    val visibleSubtitles = if (showOnlyPreferredLanguages) attachedSubtitles else fetchedSubtitles

    return StartupSubtitlePreparation(
        fetchedSubtitles = visibleSubtitles,
        attachedSubtitles = attachedSubtitles,
        fetchCompleted = true
    )
}

internal fun PlayerRuntimeController.resetAddonSubtitleStateForNewStream() {
    autoSubtitleSelected = subtitleDisabledByPersistedPreference || subtitleAddonRestoredByPersistedPreference
    hasScannedTextTracksOnce = false
    pendingAddonSubtitleLanguage = null
    pendingAddonSubtitleTrackId = null
    pendingAudioSelectionAfterSubtitleRefresh = null
    explicitSubtitleSelectionForEngineSwitch = null
    effectiveSubtitleSelectionForEngineSwitch = null
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
        secondaryLanguage = playerSettings.subtitleStyle.secondaryPreferredLanguage,
        showOnlyPreferredLanguages = playerSettings.subtitleStyle.showOnlyPreferredLanguages
    )
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
    cancelStallWatchdog()
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
    currentVideoTrackColorTransfer = null
    currentVideoTrackSelected = false
    currentVideoTrackBestSupport = C.FORMAT_UNSUPPORTED_TYPE
    lastLoggedVideoTrackSignature = null
    _uiState.update { state ->
        state.copy(
            showLoadingOverlay = state.loadingOverlayEnabled,
            showControls = false,
            loadingProgress = null
        )
    }
}

// ── CUSTOM RENDERERS FOR AUDIO/SUBTITLES ──

private class SubtitleOffsetRenderersFactory(
    context: Context,
    private val subtitleDelayUsProvider: () -> Long,
    private val audioDelayUsProvider: () -> Long,
    private val shouldNormalizeCuePositionProvider: () -> Boolean,
    private val gainAudioProcessor: GainAudioProcessor,
    private val downmixEnabled: Boolean,
    private val audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    private val downmixNormalizationEnabled: Boolean,
    private val forceOpticalPassthrough: Boolean,
    private val playbackSpeedProvider: () -> Float,
    private val initialForcePcm: Boolean = false,
    private val onPlaybackSpeedAwareAudioSinkCreated: (PlaybackSpeedAwareAudioSink) -> Unit,
    private val onFfmpegAudioRendererChanged: (FfmpegAudioRenderer?) -> Unit
) : DefaultRenderersFactory(context) {

    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        val builder = DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .setAudioProcessors(arrayOf(gainAudioProcessor))
        if (forceOpticalPassthrough) {
            builder.setAudioCapabilities(buildStableAudioCapabilities(context, true))
        }
        val baseAudioSink = builder.build()
        val playbackSpeedAwareAudioSink = PlaybackSpeedAwareAudioSink(baseAudioSink, initialForcePcm)
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
            delegate = output,
            shouldNormalizeCuePositionProvider = shouldNormalizeCuePositionProvider
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
                forceOpticalPassthrough = forceOpticalPassthrough
            )
        }
        onFfmpegAudioRendererChanged(ffmpegRenderers.firstOrNull())
    }
}
private fun FfmpegAudioRenderer.applyDownmixSettings(
    downmixEnabled: Boolean,
    audioOutputChannels: com.nuvio.tv.data.local.AudioOutputChannels,
    downmixNormalizationEnabled: Boolean,
    forceOpticalPassthrough: Boolean
) {
    setForceOpticalPassthrough(forceOpticalPassthrough)
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
    private val shouldNormalizeCuePositionProvider: () -> Boolean
) : TextOutput {

    override fun onCues(cueGroup: CueGroup) {
        val processed = cueGroup.cues.map { cue ->
            var c = fixRtlCueText(cue)
            if (shouldNormalizeCuePositionProvider()) c = normalizeCuePosition(c)
            c
        }
        delegate.onCues(CueGroup(processed, cueGroup.presentationTimeUs))
    }

    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        val processed = cues.map { cue ->
            var c = fixRtlCueText(cue)
            if (shouldNormalizeCuePositionProvider()) c = normalizeCuePosition(c)
            c
        }
        delegate.onCues(processed)
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

    private fun fixRtlCueText(cue: Cue): Cue {
        val text = cue.text ?: return cue
        if (!containsRtlChars(text)) return cue
        val original = text.toString()
        val fixed = original.split('\n').joinToString("\n") { line ->
            fixRtlPunctuationForLtr(line)
        }
        if (fixed == original) return cue
        return cue.buildUpon().setText(android.text.SpannableString(fixed)).build()
    }

    private fun fixRtlPunctuationForLtr(line: String): String {
        if (line.isEmpty()) return line
        
        var start = 0
        while (start < line.length && isRtlPunctuation(line[start])) start++
        
        var end = line.length
        while (end > start && isRtlPunctuation(line[end - 1])) end--
        
        if (start == 0 && end == line.length) return line
        
        val leadingPunct = line.substring(0, start)
        val middle = line.substring(start, end)
        val trailingPunct = line.substring(end)
        
        return "$trailingPunct$middle$leadingPunct"
    }

    private fun isRtlPunctuation(ch: Char): Boolean {
        return ch in RTL_PUNCTUATION || ch.isWhitespace()
    }

    private fun containsRtlChars(text: CharSequence): Boolean {
        for (ch in text) {
            val d = Character.getDirectionality(ch)
            if (d == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                d == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) return true
        }
        return false
    }

    companion object {
        private val RTL_PUNCTUATION = setOf('.', ',', '?', '!', '-', ':', ';', '…', ')', '(', '\'', '"')
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
        effectiveModeName == "STRIP_DV" -> fromTransfer() ?: "Strip DV"
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
