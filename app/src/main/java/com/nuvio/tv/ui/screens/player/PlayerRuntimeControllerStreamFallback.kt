package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.core.player.StreamFallbackHandoff
import com.nuvio.tv.core.player.StreamFallbackSession
import com.nuvio.tv.core.usenet.UsenetSidecar
import com.nuvio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield

internal fun PlayerRuntimeController.beginStreamFallbackSession(stream: Stream, streams: List<Stream>) {
    cancelStreamFallback()
    streamFallbackSession = StreamFallbackSession(stream, streams)
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

internal suspend fun PlayerRuntimeController.resolveFallbackCandidate(stream: Stream, season: Int?, episode: Int?): Stream =
    withTimeoutOrNull(120_000L) {
        if (stream.isUsenet()) UsenetSidecar.get(context).resolve(stream, season, episode, profileId)
        else resolveDirectDebridStreamIfNeeded(stream, season, episode)
            ?: error("Stream preparation failed")
    } ?: error("Stream preparation timed out")

internal suspend fun PlayerRuntimeController.resolveSelectedStreamWithFallback(
    stream: Stream, season: Int?, episode: Int?
): Stream? {
    val resolved = try {
        resolveFallbackCandidate(stream, season, episode)
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        streamFallbackSession?.resolveNext { candidate, attempt ->
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
    if (streamFallbackJob?.isActive == true) return true
    // A deliberate source/episode selection owns recovery until its preparation finishes.
    if (debridResolveJob?.isActive == true) return false
    val contentKey = streamCacheKey ?: return false
    val failedUrl = currentStreamUrl
    val position = currentPlaybackPositionMs()?.takeIf { it > 0L }
        ?: _uiState.value.pendingSeekPosition?.takeIf { it > 0L }
        ?: playbackTimeline.value.currentPosition.takeIf { hasRenderedFirstFrame && it > 0L }
        ?: resolvePendingInitialResumePosition().takeIf { it > 0L }
        ?: streamFallbackResumePosition ?: 0L
    val paused = userPausedManually
    if (streamFallbackSession == null) {
        streamFallbackSession = StreamFallbackHandoff.take(contentKey, profileId, failedUrl)
            ?: StreamFallbackHandoff.take(contentKey, profileId, navigationArgs.streamUrl)
    }
    if (streamFallbackSession?.canAdvance == false) return false
    streamFallbackError = detailedError
    cancelNextEpisodeAutoPlayOnFatalError()
    streamFallbackJob = scope.launch {
        // Avoid re-entering a player listener while it is still reporting the failure.
        yield()
        var discoveryScope: kotlinx.coroutines.CoroutineScope? = null
        try {
            showRecoveryOverlay()
            setLoadingStatus("stream_fallback", context.getString(R.string.player_finding_next_stream), showOverlay = true)
            if (streamFallbackSession == null) {
                // Cached links / deep links may arrive without a source-list handoff.
                loadSourceStreams(forceRefresh = false)
                discoveryScope = sourceStreamsScope
                withTimeoutOrNull(30_000L) { sourceStreamsJob?.join() }
                val streams = _uiState.value.sourceFilteredStreams.ifEmpty { _uiState.value.sourceAllStreams }
                val selected = streams.firstOrNull { it.getStreamUrl() == failedUrl }
                    ?: Stream(
                        name = null, title = null, description = null, url = failedUrl,
                        ytId = null, infoHash = _uiState.value.currentStreamInfoHash,
                        fileIdx = _uiState.value.currentStreamFileIdx, externalUrl = null,
                        behaviorHints = null, addonName = currentAddonName.orEmpty(), addonLogo = null
                    )
                streamFallbackSession = StreamFallbackSession(selected, streams)
            }
            val session = streamFallbackSession ?: return@launch
            val resolved = session.resolveNext { candidate, attempt ->
                setLoadingStatus(
                    "stream_fallback", context.getString(R.string.player_trying_next_stream, attempt), showOverlay = true
                )
                resolveFallbackCandidate(candidate, currentSeason, currentEpisode)
            }
            ensureActive()
            if (streamCacheKey != contentKey || currentStreamUrl != failedUrl || isInBackground) return@launch
            if (resolved == null) {
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
        } finally {
            // Source discovery has its own scope; it must not outlive recovery.
            if (discoveryScope != null && sourceStreamsScope === discoveryScope) {
                discoveryScope.cancel()
                sourceStreamsScope = null
                sourceStreamsJob = null
                streamRepository.setLocalPluginSearchPaused(true)
            }
        }
    }
    return true
}
