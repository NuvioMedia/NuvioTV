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
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.ForwardingRenderer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.text.TextOutput
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory
import androidx.media3.extractor.ts.TsExtractor
import androidx.media3.session.MediaSession
import com.nuvio.tv.data.local.AudioLanguageOption
import com.nuvio.tv.data.local.FrameRateMatchingMode
import io.github.peerless2012.ass.media.kt.buildWithAssSupport
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.net.SocketTimeoutException
import kotlin.math.min

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
            val playerSettings = playerSettingsDataStore.playerSettings.first()
            val useLibass = false // Temporarily disabled for maintenance
            val libassRenderType = playerSettings.libassRenderType.toAssRenderType()
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
                .setPrioritizeTimeOverSizeThresholds(true)
                .build()

            mediaSourceFactory.useParallelConnections = playerSettings.useParallelConnections
            mediaSourceFactory.parallelConnectionCount = playerSettings.parallelConnectionCount
            mediaSourceFactory.parallelChunkSizeMb = playerSettings.parallelChunkSizeMb
            mediaSourceFactory.vodCacheSizeMode = playerSettings.vodCacheSizeMode
            mediaSourceFactory.vodCacheSizeMb = playerSettings.vodCacheSizeMb

            
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
                
                when (playerSettings.preferredAudioLanguage) {
                    AudioLanguageOption.DEFAULT -> {   }
                    AudioLanguageOption.DEVICE -> {
                        
                        val deviceLanguages = if (Build.VERSION.SDK_INT >= 24) {
                            val localeList = Resources.getSystem().configuration.locales
                            Array(localeList.size()) { localeList[it].isO3Language }
                        } else {
                            arrayOf(Resources.getSystem().configuration.locale.isO3Language)
                        }
                        setParameters(
                            buildUponParameters().setPreferredAudioLanguages(*deviceLanguages)
                        )
                    }
                    else -> {
                        setParameters(
                            buildUponParameters().setPreferredAudioLanguages(
                                playerSettings.preferredAudioLanguage
                            )
                        )
                    }
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
            val mapDv7ToHevcEnabled =
                playerSettings.mapDV7ToHevc || dv7ToHevcForcedStreamUrls.contains(url)
            isMapDv7ToHevcActiveForCurrentPlayback = mapDv7ToHevcEnabled
            val codecSelector = createDolbyVisionFallbackCodecSelector(mapDv7ToHevcEnabled)
            val renderersFactory = SubtitleOffsetRenderersFactory(
                context = context,
                subtitleDelayUsProvider = subtitleDelayUs::get
            )
                .setExtensionRendererMode(playerSettings.decoderPriority)
                .setEnableDecoderFallback(true)
                .setMapDV7ToHevc(mapDv7ToHevcEnabled)
                .setMediaCodecSelector(codecSelector)

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

                
                _uiState.update { it.copy(frameRateMatchingMode = playerSettings.frameRateMatchingMode) }

                
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
                setMediaSource(mediaSourceFactory.createMediaSource(url, headers))

                playWhenReady = true
                prepare()
                startFrameRateProbe(
                    url,
                    headers,
                    playerSettings.frameRateMatchingMode != FrameRateMatchingMode.OFF
                )

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
                        if (error.isDolbyVisionDecoderFailure() &&
                            !isMapDv7ToHevcActiveForCurrentPlayback
                        ) {
                            Log.w(
                                PlayerRuntimeController.TAG,
                                "Dolby Vision decoder failure detected, retrying with DV7->HEVC " +
                                    "fallback host=${Uri.parse(currentStreamUrl).host ?: "unknown"} " +
                                    "positionMs=$currentPosition"
                            )
                            dv7ToHevcForcedStreamUrls.add(currentStreamUrl)
                            retryCurrentStreamWithDolbyVisionFallback(currentPosition)
                            return
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

internal fun PlayerRuntimeController.resetLoadingOverlayForNewStream() {
    hasRenderedFirstFrame = false
    shouldEnforceAutoplayOnFirstReady = true
    userPausedManually = false
    timeoutRecoveryAttempts = 0
    hasRetriedCurrentStreamAfterUnexpectedNpe = false
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
    renderer: Renderer,
    private val subtitleDelayUsProvider: () -> Long
) : ForwardingRenderer(renderer) {

    override fun render(positionUs: Long, elapsedRealtimeUs: Long) {
        val adjustedPositionUs = (positionUs - subtitleDelayUsProvider()).coerceAtLeast(0L)
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
