package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import com.nuvio.tv.R
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private val SOURCE_FALLBACK_HTTP_CODES = setOf(403, 404, 410, 416, 500, 503)

private fun PlayerRuntimeController.showRecoveryLoadingOverlay(message: String) {
    activeRecoveryLoadingMessage = message
    _uiState.update {
        it.copy(
            error = null,
            showLoadingOverlay = true,
            loadingMessage = message,
            showPauseOverlay = false,
            showControls = false
        )
    }
}

internal fun PlayerRuntimeController.resetSeamlessRecoveryState() {
    seamlessRecoveryEngineSwitchUsed = false
    startupEngineFailoverTriggered = false
}

private fun PlayerRuntimeController.resetSourceRecoveryFlags() {
    hasTriedAudioPcmFallback = false
    hasTriedDv7HevcFallback = false
    forceDv7ToHevc = false
    hasRetriedCurrentStreamAfter416 = false
    resetErrorRetryState()
}

private fun PlayerRuntimeController.nextRecoverySourceIndex(): Int? {
    val resolvedIndex = currentStreamSourceUrls.indexOf(currentStreamUrl)
    return if (resolvedIndex >= 0) {
        currentStreamSourceUrls.indices.firstOrNull { it > resolvedIndex }
    } else {
        currentStreamSourceUrls.indices.firstOrNull()
    }
}

private fun PlayerRuntimeController.clearReuseLastLinkLiveRecovery() {
    pendingReuseLastLinkLiveRecovery = null
}

private fun PlayerRuntimeController.finishReuseLastLinkBootstrap() {
    startedFromReuseLastLink = false
    pendingReuseLastLinkLiveRecovery = null
}

private fun PlayerRuntimeController.showReuseLastLinkRecoveryFailed(detailedError: String) {
    startedFromReuseLastLink = false
    activeRecoveryLoadingMessage = null
    clearReuseLastLinkLiveRecovery()
    _uiState.update {
        it.copy(
            error = detailedError,
            showLoadingOverlay = false,
            showPauseOverlay = false,
            isBuffering = false
        )
    }
}

private fun PlayerRuntimeController.tryReuseLastLinkLiveRecovery(
    detailedError: String,
    resumePosition: Long,
    allowEngineFailover: Boolean,
    startup: Boolean
): Boolean {
    if (!startedFromReuseLastLink) return false
    if (hasRenderedFirstFrame) return false
    if (pendingReuseLastLinkLiveRecovery != null) return true
    if (hasAttemptedReuseLastLinkLiveRecovery) return false

    hasAttemptedReuseLastLinkLiveRecovery = true
    pendingReuseLastLinkLiveRecovery = PlayerRuntimeController.PendingReuseLastLinkLiveRecovery(
        detailedError = detailedError,
        resumePosition = resumePosition,
        allowEngineFailover = allowEngineFailover,
        startup = startup
    )
    showRecoveryLoadingOverlay(context.getString(R.string.player_loading_reuse_last_link_refresh))
    loadSourceStreams(forceRefresh = true)
    return true
}

internal fun PlayerRuntimeController.continueReuseLastLinkLiveRecoveryIfNeeded(): Boolean {
    val pending = pendingReuseLastLinkLiveRecovery ?: return false
    val nextIndex = nextRecoverySourceIndex()
    if (nextIndex != null) {
        finishReuseLastLinkBootstrap()
        return switchToNextSource(
            nextIndex = nextIndex,
            message = context.getString(
                if (pending.startup) R.string.player_loading_fallback_next_source_startup
                else R.string.player_loading_fallback_next_source,
                nextIndex + 1,
                currentStreamSourceUrls.size
            ),
            resumePosition = pending.resumePosition,
            preferInPlayerSourceSwap = !pending.startup && !isUsingMpvEngine()
        )
    }

    clearReuseLastLinkLiveRecovery()
    return if (pending.allowEngineFailover) {
        finishReuseLastLinkBootstrap()
        switchRecoveryEngineAndRestartSources(
            detailedError = pending.detailedError,
            allowEngineFailover = true,
            resumePosition = pending.resumePosition
        )
    } else {
        showReuseLastLinkRecoveryFailed(pending.detailedError)
        false
    }
}

internal fun PlayerRuntimeController.failReuseLastLinkLiveRecovery(errorMessage: String?) {
    val pending = pendingReuseLastLinkLiveRecovery ?: return
    clearReuseLastLinkLiveRecovery()
    if (pending.allowEngineFailover) {
        finishReuseLastLinkBootstrap()
        switchRecoveryEngineAndRestartSources(
            detailedError = pending.detailedError,
            allowEngineFailover = true,
            resumePosition = pending.resumePosition
        )
        return
    }
    showReuseLastLinkRecoveryFailed(errorMessage ?: pending.detailedError)
}

private fun PlayerRuntimeController.updateCurrentStreamTracking(
    candidate: RecoverySourceCandidate,
    index: Int
) {
    currentStreamUrl = candidate.url
    currentStreamSourceIndex = index
    currentHeaders = candidate.headers
    currentFilename = candidate.filename
    currentStreamResponseHeaders = candidate.responseHeaders
    currentStreamBingeGroup = candidate.bingeGroup
    currentVideoHash = candidate.videoHash
    currentVideoSize = candidate.videoSize
    currentAddonName = candidate.addonName
    currentAddonLogo = candidate.addonLogo
    currentStreamDescription = candidate.streamDescription
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = candidate.url,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )
}

private fun PlayerRuntimeController.scheduleRecoveryRestart(
    targetUrl: String,
    targetIndex: Int,
    message: String,
    resumePosition: Long,
    overrideInternalPlayerEngine: InternalPlayerEngine? = null,
    showEngineSwitchInfo: Boolean = false,
    engineSwitchInfoText: String = message
) {
    val targetEngine = overrideInternalPlayerEngine ?: currentInternalPlayerEngine
    val switchingToMpv = targetEngine == InternalPlayerEngine.MVP_PLAYER
    val targetCandidate = currentStreamSourceCandidates.getOrNull(targetIndex)
        ?: RecoverySourceCandidate(
            url = targetUrl,
            streamName = _uiState.value.currentStreamName,
            headers = currentHeaders,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders,
            bingeGroup = currentStreamBingeGroup,
            videoHash = currentVideoHash,
            videoSize = currentVideoSize,
            addonName = currentAddonName,
            addonLogo = currentAddonLogo,
            streamDescription = currentStreamDescription
        )
    updateCurrentStreamTracking(candidate = targetCandidate, index = targetIndex)
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryLoadingOverlay(message)
        hidePlayerEngineSwitchInfoJob?.cancel()
        _uiState.update {
            it.copy(
                currentStreamUrl = targetUrl,
                currentStreamName = targetCandidate.streamName,
                internalPlayerEngine = targetEngine,
                showPlayerEngineSwitchInfo = showEngineSwitchInfo,
                playerEngineSwitchInfoText = if (showEngineSwitchInfo) engineSwitchInfoText else it.playerEngineSwitchInfoText
            )
        }
        // Moved to bottom
        pendingMpvHardRestartOnNextAttach = switchingToMpv
        delayMpvResumeSeekUntilVideoTrack = switchingToMpv
        currentInternalPlayerEngine = targetEngine
        releasePlayer(flushPlaybackState = false)
        if (resumePosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = resumePosition) }
        }
        initializePlayer(
            url = targetUrl,
            headers = currentHeaders,
            overrideInternalPlayerEngine = overrideInternalPlayerEngine,
            allowEngineFailover = false,
            resetSeamlessRecoveryPlan = false
        )
        if (showEngineSwitchInfo) {
            hidePlayerEngineSwitchInfoJob = scope.launch {
                delay(2200)
                _uiState.update { state -> state.copy(showPlayerEngineSwitchInfo = false) }
            }
        }
    }
}

private fun PlayerRuntimeController.switchToNextSource(
    nextIndex: Int,
    message: String,
    resumePosition: Long,
    preferInPlayerSourceSwap: Boolean
): Boolean {
    val nextCandidate = currentStreamSourceCandidates.getOrNull(nextIndex) ?: return false
    val nextUrl = nextCandidate.url
    Log.w(
        PlayerRuntimeController.TAG,
        "Source recovery: switching to source ${nextIndex + 1}/${currentStreamSourceUrls.size}, position=${resumePosition}ms"
    )
    resetSourceRecoveryFlags()
    updateCurrentStreamTracking(candidate = nextCandidate, index = nextIndex)
    showRecoveryLoadingOverlay(message)
    _uiState.update {
        it.copy(
            currentStreamUrl = nextUrl,
            currentStreamName = nextCandidate.streamName
        )
    }

    val player = _exoPlayer
    if (preferInPlayerSourceSwap && player != null && !isUsingMpvEngine()) {
        val subtitleConfigurations = _uiState.value.addonSubtitles
            .distinctBy { "${it.id}|${it.url}" }
            .map(::toSubtitleConfiguration)
        player.setMediaSource(
            mediaSourceFactory.createMediaSource(
                url = nextUrl,
                headers = currentHeaders,
                subtitleConfigurations = subtitleConfigurations,
                filename = currentFilename,
                responseHeaders = currentStreamResponseHeaders,
                mimeTypeOverride = currentStreamMimeType
            ),
            resumePosition
        )
        player.prepare()
        player.playWhenReady = true
        return true
    }

    scheduleRecoveryRestart(
        targetUrl = nextUrl,
        targetIndex = nextIndex,
        message = message,
        resumePosition = resumePosition
    )
    return true
}

/**
 * Determines whether the given [PlaybackException] is transient and worth retrying.
 *
 * Retryable errors include source/IO errors, parsing glitches, and unexpected runtime
 * exceptions that commonly occur after pause/resume or seek on flaky streams.
 * Decoder-init and DRM errors are considered fatal.
 */
internal fun isRetryablePlaybackError(error: PlaybackException): Boolean {
    return when (error.errorCode) {
        // --- Source / IO errors (the 2xxx range) ---
        PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
        PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
        PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED,

        // --- Decoder errors (often transient after pause/resume on some hardware) ---
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
}

internal fun PlaybackException.findInvalidResponseCodeException(): HttpDataSource.InvalidResponseCodeException? {
    var current: Throwable? = cause
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) return current
        current = current.cause
    }
    return null
}

internal fun PlaybackException.toDisplayMessage(): String {
    val responseException = findInvalidResponseCodeException()
    if (responseException != null) {
        val statusText = responseException.responseMessage?.takeIf { it.isNotBlank() }
        return buildString {
            append("HTTP ")
            append(responseException.responseCode)
            statusText?.let {
                append(' ')
                append(it)
            }
            append(" [")
            append(errorCodeName)
            append(']')
        }
    }

    val meaningfulMessage = findMostRelevantCauseMessage()
    return if (meaningfulMessage != null) {
        "$meaningfulMessage [$errorCodeName]"
    } else {
        errorCodeName
    }
}

internal fun Throwable.toDisplayMessage(fallback: String = "Playback error"): String {
    val meaningfulMessage = findMostRelevantCauseMessage()
    return meaningfulMessage ?: message?.takeIf { it.isNotBlank() } ?: fallback
}

private fun Throwable.findMostRelevantCauseMessage(): String? {
    val candidates = buildList {
        var current: Throwable? = this@findMostRelevantCauseMessage
        while (current != null) {
            current.message
                ?.trim()
                ?.takeIf {
                    it.isNotBlank() &&
                        !it.equals("Playback error", ignoreCase = true) &&
                        !it.equals("Source error", ignoreCase = true) &&
                        !it.equals("Unexpected runtime error", ignoreCase = true)
                }
                ?.let(::add)
            current = current.cause
        }
    }
    return candidates.firstOrNull()
}

internal fun PlayerRuntimeController.tryFallbackToNextSource(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!cachedAutoSourceFallback) return false
    val responseException = error.findInvalidResponseCodeException() ?: return false
    if (responseException.responseCode !in SOURCE_FALLBACK_HTTP_CODES) return false

    val nextIndex = currentStreamSourceIndex + 1
    if (currentStreamSourceUrls.getOrNull(nextIndex) == null) return false
    val resumePosition = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L

    Log.w(
        PlayerRuntimeController.TAG,
        "Switching to fallback source ${nextIndex + 1}/${currentStreamSourceUrls.size} after HTTP ${responseException.responseCode}: $detailedError"
    )

    return switchToNextSource(
        nextIndex = nextIndex,
        message = context.getString(
            R.string.player_loading_fallback_http_next_source,
            responseException.responseCode,
            nextIndex + 1,
            currentStreamSourceUrls.size
        ),
        resumePosition = resumePosition,
        preferInPlayerSourceSwap = true
    )
}

/**
 * Attempts an automatic retry of the current stream, preserving the playback position.
 *
 * The player is fully torn down and re-initialised so that internal ExoPlayer state
 * (extractors, loaders, renderers) is clean - this is the most reliable way to recover
 * from the class of errors reported by users (corrupt parser state after pause/seek).
 *
 * Returns `true` if a retry was scheduled, `false` if the error should be shown to the user.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.attemptAutoRetry(
    error: PlaybackException,
    detailedError: String
): Boolean {
    if (!isRetryablePlaybackError(error)) return false
    if (errorRetryCount >= MAX_AUTO_RETRIES) return false

    // Skip auto-retry for startup failures if we have alternative streams to fall back to.
    // This prevents the user from being stuck staring at "Auto-retry 1/2" for every dead stream proxy.
    val hasMoreStreams = nextRecoverySourceIndex() != null
    if (!hasRenderedFirstFrame && cachedAutoSourceFallback && hasMoreStreams) {
        return false // Let tryAutoSourceFallback take over immediately
    }

    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto-retry ${attempt + 1}/$MAX_AUTO_RETRIES after ${RETRY_DELAY_MS}ms for: $detailedError"
    )

    // Capture the current position so we can resume after re-init.
    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L
    val isFirstAttempt = attempt == 0

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        val retryMessage = context.getString(
            if (isFirstAttempt) R.string.player_loading_retry_stream else R.string.player_loading_retry_stream_rebuild,
            attempt + 1,
            MAX_AUTO_RETRIES
        )
        showRecoveryLoadingOverlay(retryMessage)

        delay(RETRY_DELAY_MS)

        if (isFirstAttempt) {
            // Lightweight recovery: re-prepare the same source without destroying
            // the player. Keeps the last frame visible for a seamless experience.
            val player = _exoPlayer
            if (player != null) {
                if (savedPosition > 0L) {
                    player.seekTo((savedPosition - 1).coerceAtLeast(0L))
                }
                player.prepare()
                player.playWhenReady = true
            } else {
                releasePlayer(flushPlaybackState = false)
                if (savedPosition > 0L) {
                    _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
                }
                initializePlayer(currentStreamUrl, currentHeaders)
            }
        } else {
            // Full teardown — clears any corrupt decoder/internal state.
            releasePlayer(flushPlaybackState = false)
            if (savedPosition > 0L) {
                _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
            }
            initializePlayer(currentStreamUrl, currentHeaders)
        }
    }
    return true
}

/**
 * Resets the retry counter. Call this whenever playback enters a healthy state
 * (first frame rendered, or user-initiated retry).
 */
internal fun PlayerRuntimeController.resetErrorRetryState() {
    errorRetryCount = 0
    errorRetryJob?.cancel()
    errorRetryJob = null
    activeRecoveryLoadingMessage = null
}

/**
 * Silent PCM audio fallback for ERROR_CODE_AUDIO_TRACK_INIT_FAILED (5001).
 *
 * When the decoder is set to EXTENSION_RENDERER_MODE_ON (decoderPriority == 1,
 * the default) and tunneling is NOT active, audio passthrough may fail on certain devices/formats.
 * Instead of tearing down and re-building the entire player, we apply an
 * imperceptible speed change (1.00001×) which forces ExoPlayer to decode audio
 * through the software PCM pipeline — identical to what happens when the user
 * manually changes playback speed.
 *
 * This is a one-shot attempt per stream; if it fails again the normal retry
 * logic takes over.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAudioTrackPcmFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED) return false
    if (hasTriedAudioPcmFallback) return false
    if (cachedDecoderPriority != 1) return false // Only for EXTENSION_RENDERER_MODE_ON
    if (_uiState.value.tunnelingEnabled) return false

    hasTriedAudioPcmFallback = true

    val player = _exoPlayer ?: return false
    val savedPosition = player.currentPosition.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Audio track init failed (5001) — forcing PCM via speed trick, position=${savedPosition}ms"
    )

    // Show loading overlay with fallback info instead of error screen.
    val fallbackMessage = context.getString(R.string.player_loading_fallback_pcm_audio)
    showRecoveryLoadingOverlay(fallbackMessage)

    // An imperceptible speed offset disables audio passthrough and forces
    // software PCM decoding through the GainAudioProcessor pipeline.
    val currentSpeed = _uiState.value.playbackSpeed
    val pcmSpeed = if (currentSpeed == 1f) 1.00001f else currentSpeed
    player.playbackParameters = PlaybackParameters(pcmSpeed)

    if (savedPosition > 0L) {
        player.seekTo(savedPosition)
    }
    player.prepare()
    player.playWhenReady = true

    return true
}

/**
 * DV7-to-HEVC decoder fallback for ERROR_CODE_DECODER_INIT_FAILED (4003).
 *
 * When decoderPriority == 1 (EXTENSION_RENDERER_MODE_ON) and the decoder
 * fails to initialise, this is often caused by Dolby Vision profile 7
 * content on devices without a DV decoder.  Enabling the DV7-to-HEVC
 * mapping allows the HEVC decoder to handle the stream instead.
 *
 * Unlike the PCM fallback this requires a full player rebuild because
 * the mapping is baked into the renderers factory at build time.
 * Tunneling state does not matter for this fallback.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryDv7HevcFallback(
    error: PlaybackException
): Boolean {
    if (error.errorCode != PlaybackException.ERROR_CODE_DECODER_INIT_FAILED) return false
    if (hasTriedDv7HevcFallback) return false
    if (cachedDecoderPriority != 1) return false
    // Skip if DV7-to-HEVC is already active — nothing more we can do.
    if (forceDv7ToHevc) return false

    hasTriedDv7HevcFallback = true
    forceDv7ToHevc = true

    val savedPosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.d(
        PlayerRuntimeController.TAG,
        "Decoder init failed (4003) — retrying with DV7-to-HEVC mapping, position=${savedPosition}ms"
    )

    resetErrorRetryState()

    // Show loading overlay with fallback info instead of error screen.
    val fallbackMessage = context.getString(R.string.player_loading_fallback_hevc_decoder)
    errorRetryJob = scope.launch {
        showRecoveryLoadingOverlay(fallbackMessage)

        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders)
    }
    return true
}

/**
 * Last-resort source fallback for any unrecoverable error.
 *
 * When [cachedAutoSourceFallback] is enabled and there are remaining source
 * URLs in [currentStreamSourceUrls], this switches to the next source.
 * ExoPlayer keeps the fast in-player swap path; mpv falls back to a rebuild.
 *
 * All decoder fallback flags (PCM, DV7-HEVC) are reset so they can
 * re-attempt on the new source.
 */
@androidx.annotation.OptIn(UnstableApi::class)
internal fun PlayerRuntimeController.tryAutoSourceFallback(
    error: PlaybackException
): Boolean {
    if (!cachedAutoSourceFallback) return false

    var nextIndex = nextRecoverySourceIndex()
    if (nextIndex == null && refreshRecoverySourceCandidatesFromSourcePool()) {
        nextIndex = nextRecoverySourceIndex()
    }
    val savedPosition = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L
    if (nextIndex == null) {
        return tryReuseLastLinkLiveRecovery(
            detailedError = error.errorCodeName ?: "Playback error",
            resumePosition = savedPosition,
            allowEngineFailover = autoSwitchInternalPlayerOnErrorEnabled,
            startup = !hasRenderedFirstFrame
        )
    }

    Log.w(
        PlayerRuntimeController.TAG,
        "Auto source fallback: switching to source ${nextIndex + 1}/${currentStreamSourceUrls.size}" +
            " after error ${error.errorCode}, position=${savedPosition}ms"
    )

    return switchToNextSource(
        nextIndex = nextIndex,
        message = context.getString(
            R.string.player_loading_fallback_next_source,
            nextIndex + 1,
            currentStreamSourceUrls.size
        ),
        resumePosition = savedPosition,
        preferInPlayerSourceSwap = !isUsingMpvEngine()
    )
}

private fun PlayerRuntimeController.nextRecoveryEngineOrNull(allowEngineFailover: Boolean): InternalPlayerEngine? {
    if (!allowEngineFailover) return null
    if (!autoSwitchInternalPlayerOnErrorEnabled) return null
    if (seamlessRecoveryEngineSwitchUsed) return null
    return when (currentInternalPlayerEngine) {
        InternalPlayerEngine.EXOPLAYER -> InternalPlayerEngine.MVP_PLAYER
        InternalPlayerEngine.MVP_PLAYER -> InternalPlayerEngine.EXOPLAYER
    }
}

private fun PlayerRuntimeController.switchRecoveryEngineAndRestartSources(
    detailedError: String,
    allowEngineFailover: Boolean,
    resumePosition: Long
): Boolean {
    val targetEngine = nextRecoveryEngineOrNull(allowEngineFailover) ?: return false
    val restartSourcesFromBeginning = seamlessPlaybackModeSetting == com.nuvio.tv.data.local.SeamlessPlaybackMode.STREAM_THEN_PLAYER
    val targetIndex = if (restartSourcesFromBeginning) 0 else currentStreamSourceIndex
    val targetUrl = currentStreamSourceUrls.getOrNull(targetIndex)?.takeIf { it.isNotBlank() } ?: currentStreamUrl
    val targetEngineLabel = when (targetEngine) {
        InternalPlayerEngine.EXOPLAYER -> context.getString(R.string.playback_engine_exoplayer)
        InternalPlayerEngine.MVP_PLAYER -> context.getString(R.string.playback_engine_mvplayer)
    }
    val switchInfoText = context.getString(R.string.player_engine_switching_message, targetEngineLabel)
    Log.w(
        PlayerRuntimeController.TAG,
        "Engine recovery: switching to $targetEngine after: $detailedError"
    )
    seamlessRecoveryEngineSwitchUsed = true
    startupEngineFailoverTriggered = true
    resetSourceRecoveryFlags()
    scheduleRecoveryRestart(
        targetUrl = targetUrl,
        targetIndex = targetIndex,
        message = if (restartSourcesFromBeginning) {
            context.getString(
                R.string.player_loading_switch_engine_restart_sources,
                targetEngineLabel,
                currentStreamSourceUrls.size
            )
        } else {
            context.getString(R.string.player_loading_switch_engine_same_source, targetEngineLabel)
        },
        resumePosition = resumePosition,
        overrideInternalPlayerEngine = targetEngine,
        showEngineSwitchInfo = true,
        engineSwitchInfoText = switchInfoText
    )
    return true
}

internal fun PlayerRuntimeController.attemptSeamlessPlaybackRecovery(
    error: PlaybackException,
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (tryAutoSourceFallback(error)) {
        return true
    }
    val savedPosition = currentPlaybackPositionMs()?.takeIf { it > 0L } ?: 0L
    return switchRecoveryEngineAndRestartSources(
        detailedError = detailedError,
        allowEngineFailover = allowEngineFailover,
        resumePosition = savedPosition
    )
}

internal fun PlayerRuntimeController.attemptSeamlessStartupRecovery(
    detailedError: String,
    allowEngineFailover: Boolean
): Boolean {
    if (cachedAutoSourceFallback) {
        var nextIndex = nextRecoverySourceIndex()
        if (nextIndex == null && refreshRecoverySourceCandidatesFromSourcePool()) {
            nextIndex = nextRecoverySourceIndex()
        }
        if (nextIndex != null) {
            Log.w(
                PlayerRuntimeController.TAG,
                "Startup recovery: switching to source ${nextIndex + 1}/${currentStreamSourceUrls.size} after: $detailedError"
            )
            return switchToNextSource(
                nextIndex = nextIndex,
                message = context.getString(
                    R.string.player_loading_fallback_next_source_startup,
                    nextIndex + 1,
                    currentStreamSourceUrls.size
                ),
                resumePosition = 0L,
                preferInPlayerSourceSwap = false
            )
        }
    }
    if (tryReuseLastLinkLiveRecovery(detailedError, resumePosition = 0L, allowEngineFailover = allowEngineFailover, startup = true)) {
        return true
    }
    return switchRecoveryEngineAndRestartSources(
        detailedError = detailedError,
        allowEngineFailover = allowEngineFailover,
        resumePosition = 0L
    )
}
