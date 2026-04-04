package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.HttpDataSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTO_RETRIES = 2
private const val RETRY_DELAY_MS = 1_500L
private val SOURCE_FALLBACK_HTTP_CODES = setOf(403, 404, 410, 416, 500, 503)

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
    val responseException = error.findInvalidResponseCodeException() ?: return false
    if (responseException.responseCode !in SOURCE_FALLBACK_HTTP_CODES) return false

    val nextIndex = currentStreamSourceIndex + 1
    val nextUrl = currentStreamSourceUrls.getOrNull(nextIndex) ?: return false
    val resumePosition = _exoPlayer?.currentPosition?.takeIf { it > 0L } ?: 0L

    Log.w(
        PlayerRuntimeController.TAG,
        "Switching to fallback source ${nextIndex + 1}/${currentStreamSourceUrls.size} after HTTP ${responseException.responseCode}: $detailedError"
    )

    resetErrorRetryState()
    hasRetriedCurrentStreamAfter416 = false
    currentStreamUrl = nextUrl
    currentStreamSourceIndex = nextIndex
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = nextUrl,
        filename = currentFilename,
        responseHeaders = currentStreamResponseHeaders
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                currentStreamUrl = nextUrl,
                error = null,
                showLoadingOverlay = it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        releasePlayer(flushPlaybackState = false)
        if (resumePosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = resumePosition) }
        }
        initializePlayer(nextUrl, currentHeaders)
    }
    return true
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
        _uiState.update {
            it.copy(
                error = null,
                // Only show loading overlay on full teardown (second attempt).
                showLoadingOverlay = if (isFirstAttempt) false else it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

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
}
