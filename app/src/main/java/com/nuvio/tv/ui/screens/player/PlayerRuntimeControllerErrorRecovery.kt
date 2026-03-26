package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.PlaybackException
import androidx.media3.common.util.UnstableApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val MAX_AUTO_RETRIES = 1
private const val RETRY_DELAY_MS = 1_500L

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
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> true

        // --- Behind-the-scenes / unexpected errors (often IllegalStateException / NPE) ---
        PlaybackException.ERROR_CODE_UNSPECIFIED -> {
            val cause = error.cause
            cause is IllegalStateException || cause is NullPointerException
        }

        else -> false
    }
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

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        _uiState.update {
            it.copy(
                error = null,
                showLoadingOverlay = it.loadingOverlayEnabled,
                showPauseOverlay = false
            )
        }

        delay(RETRY_DELAY_MS)

        // Full teardown — clears any corrupt internal state.
        releasePlayer(flushPlaybackState = false)

        // Stash position so initializePlayer's STATE_READY handler will seek to it.
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }

        initializePlayer(currentStreamUrl, currentHeaders)
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
