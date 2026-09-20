package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamFallbackSession
import com.nuvio.tv.core.usenet.UsenetSettings
import com.nuvio.tv.core.usenet.UsenetSidecar
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

internal fun PlayerRuntimeController.beginStreamFallbackSession(stream: Stream, streams: List<Stream>) {
    cancelStreamFallback()
    streamFallbackSession = if (stream.isUsenet()) {
        StreamFallbackSession(stream, streams, isEnabled = { UsenetSettings.read(context).fallbackEnabled })
    } else null
    streamFallbackResumePosition = null
}

internal fun PlayerRuntimeController.cancelStreamFallback(showError: Boolean = false) {
    if (showError && streamFallbackJob?.isActive == true) {
        _uiState.update {
            it.copy(error = streamFallbackError, isBuffering = false, showLoadingOverlay = false)
        }
    }
    streamFallbackJob?.cancel()
    streamFallbackJob = null
}

internal suspend fun PlayerRuntimeController.resolveFallbackCandidate(stream: Stream, season: Int?, episode: Int?): Stream {
    require(stream.isUsenet()) { "Fallback only supports Usenet sources" }
    return withTimeoutOrNull(120_000L) {
        UsenetSidecar.get(context).resolve(stream, season, episode, profileId)
    } ?: error("Stream preparation timed out")
}

internal suspend fun PlayerRuntimeController.resolveSelectedStreamWithFallback(
    stream: Stream, season: Int?, episode: Int?
): Stream? {
    require(stream.isUsenet()) { "Fallback only supports Usenet sources" }
    val resolved = try {
        resolveFallbackCandidate(stream, season, episode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (error: Exception) {
        val session = streamFallbackSession
        if (session == null || !session.canAdvance) throw error
        session.resolveNext { candidate, attempt ->
            setLoadingStatus("stream_fallback", context.getString(R.string.player_trying_next_stream, attempt), showOverlay = true)
            resolveFallbackCandidate(candidate, season, episode)
        }
    }
    resolved?.let { streamFallbackSession?.resolved(it) }
    return resolved
}

/** Called only once the existing same-source/decoder recovery ladder has given up. */
internal fun PlayerRuntimeController.tryNextStream(detailedError: String): Boolean {
    if (isInBackground || isReleasingPlayer || contentType.equals("cloud", ignoreCase = true)) return false
    val session = streamFallbackSession ?: return false
    val failedUrl = currentStreamUrl
    if (!session.canFallbackFrom(failedUrl)) return false
    if (streamFallbackJob?.isActive == true) return true
    // A deliberate source/episode selection owns recovery until its preparation finishes.
    if (debridResolveJob?.isActive == true) return false
    val contentKey = streamCacheKey ?: return false
    val position = currentPlaybackPositionMs()?.takeIf { it > 0L }
        ?: _uiState.value.pendingSeekPosition?.takeIf { it > 0L }
        ?: playbackTimeline.value.currentPosition.takeIf { hasRenderedFirstFrame && it > 0L }
        ?: resolvePendingInitialResumePosition().takeIf { it > 0L }
        ?: streamFallbackResumePosition ?: 0L
    val paused = userPausedManually
    streamFallbackError = detailedError
    cancelNextEpisodeAutoPlayOnFatalError()
    streamFallbackJob = scope.launch {
        // Avoid re-entering a player listener while it is still reporting the failure.
        yield()
        try {
            showRecoveryOverlay()
            setLoadingStatus("stream_fallback", context.getString(R.string.player_finding_next_stream), showOverlay = true)
            val resolved = session.resolveNext { candidate, attempt ->
                setLoadingStatus(
                    "stream_fallback", context.getString(R.string.player_trying_next_stream, attempt), showOverlay = true
                )
                resolveFallbackCandidate(candidate, currentSeason, currentEpisode)
            }
            ensureActive()
            if (streamCacheKey != contentKey || currentStreamUrl != failedUrl ||
                streamFallbackSession !== session || isInBackground) return@launch
            if (resolved == null || !session.enabled) {
                _uiState.update {
                    it.copy(
                        error = context.getString(R.string.player_stream_fallback_exhausted) + "\n" + detailedError,
                        isBuffering = false, showLoadingOverlay = false, showPauseOverlay = false
                    )
                }
            } else {
                streamFallbackResumePosition = position
                streamFallbackStartPaused = paused
                // Allow an immediately failing replacement to schedule its own fallback.
                streamFallbackJob = null
                switchToSourceStream(resolved)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            _uiState.update { it.copy(error = detailedError, isBuffering = false, showLoadingOverlay = false) }
        }
    }
    return true
}
