package com.nuvio.tv.ui.screens.player

import android.widget.Toast
import androidx.annotation.StringRes
import com.nuvio.tv.R
import com.nuvio.tv.data.mediaserver.ServerPlaybackSession
import com.nuvio.tv.data.mediaserver.ServerTrack
import com.nuvio.tv.data.mediaserver.labelRes
import com.nuvio.tv.data.mediaserver.readableTranscodeReason
import com.nuvio.tv.data.mediaserver.serverPlaybackMessageRes
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.model.enabledAddons
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

internal fun PlayerRuntimeController.reportServerPlayback() {
    val url = currentStreamUrl
    if (url != reportedServerUrl) {
        serverPlayback.stop(reportedServerUrl)
        reportedServerUrl = url.takeIf(serverPlayback::isServerSource)
        refreshServerTracks()
    }
    val state = _uiState.value
    serverPlayback.onPlaybackSnapshot(
        url = reportedServerUrl,
        positionMs = _playbackTimeline.value.currentPosition,
        isPlaying = state.isPlaying,
        isLoading = state.isBuffering,
        isEnded = state.playbackEnded
    )
}

internal fun PlayerRuntimeController.stopServerPlayback() {
    serverPlayback.stop(reportedServerUrl ?: currentStreamUrl)
    reportedServerUrl = null
}

internal val PlayerRuntimeController.isServerStream: Boolean
    get() = serverPlayback.isServerSource(currentStreamUrl)

internal val PlayerRuntimeController.hasBurnedInServerSubtitle: Boolean
    get() = serverPlayback.burnInSubtitles(currentStreamUrl).any { it.selected }

internal fun PlayerRuntimeController.serverPlaybackSummary(): String? {
    val session = serverPlayback.session(currentStreamUrl) ?: return null
    val method = context.getString(session.playMethod.labelRes())
    val reasons = session.transcodeReasons.joinToString(", ", transform = ::readableTranscodeReason)
    return if (reasons.isEmpty()) method else "$method · $reasons"
}

internal suspend fun PlayerRuntimeController.newerServerProgress(saved: WatchProgress?): WatchProgress? {
    if (!isServerStream) return saved
    val state = try {
        withTimeoutOrNull(SERVER_RESUME_TIMEOUT_MS) { serverPlayback.resumeState(currentStreamUrl) }
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    } ?: return saved
    val lastPlayed = state.lastPlayedEpochMs
        ?.takeIf { !state.played && state.positionMs > 0L && state.durationMs > 0L }
        ?: return saved
    if (saved != null && saved.lastWatched >= lastPlayed) return saved
    val parentContentId = contentId ?: return saved
    val base = saved ?: currentWatchProgress(parentContentId, contentType ?: "movie", 0L, 0L, 0L)
    return base.copy(position = state.positionMs, duration = state.durationMs, lastWatched = lastPlayed, progressPercent = null)
}

internal fun PlayerRuntimeController.serverImdbId(contentId: String): String? =
    metaRepository.getCachedMeta(contentType ?: "movie", contentId)?.imdbId?.takeIf { it.startsWith("tt") }

internal suspend fun PlayerRuntimeController.streamAddonsFor(videoId: String): List<Addon> =
    if (serverStreams.isNativeRequest(videoId)) emptyList() else addonRepository.getInstalledAddons().first().enabledAddons()

internal fun PlayerRuntimeController.serverSourceNames(type: String, videoId: String): List<String> =
    serverStreams.sources(type, videoId, season = null, episode = null).map { it.name }

internal suspend fun PlayerRuntimeController.prepareServerStream(stream: Stream): Stream? {
    val target = stream.serverTarget ?: return stream
    val session = try {
        serverPlayback.prepare(target)
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        Toast.makeText(context, context.getString(error.serverPlaybackMessageRes()), Toast.LENGTH_SHORT).show()
        return null
    }
    val hints = stream.behaviorHints ?: StreamBehaviorHints(null, null, null, null)
    return stream.copy(
        url = session.url,
        subtitles = session.subtitles + stream.subtitles,
        behaviorHints = hints.copy(proxyHeaders = session.headers.takeIf { it.isNotEmpty() }?.let { ProxyHeaders(request = it, response = null) })
    )
}

internal fun PlayerRuntimeController.tryServerFallback(): Boolean {
    val failedUrl = currentStreamUrl
    if (!serverPlayback.canFallback(failedUrl)) return false
    val positionMs = _playbackTimeline.value.currentPosition
    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        val session = serverPlayback.fallback(failedUrl)
        if (session == null) {
            _uiState.update {
                it.copy(
                    error = context.getString(R.string.player_error_play_stream_failed),
                    isBuffering = false,
                    showLoadingOverlay = false,
                    showPauseOverlay = false
                )
            }
            return@launch
        }
        restartServerSession(session, positionMs)
    }
    return true
}

internal fun PlayerRuntimeController.selectServerAudio(index: Int) {
    serverAudioChosenByUser = true
    switchServerAudio(index)
}

internal fun PlayerRuntimeController.selectServerSubtitle(index: Int) {
    if (_uiState.value.serverSubtitleTracks.any { it.isSelected && it.index == index }) return
    disableSubtitles()
    isUserExplicitSubtitleSelection = true
    persistedTrackPreference = persistedTrackPreference?.copy(subtitle = null)
    pendingRestoredAddonSubtitle = null
    restartServerStream(R.string.servers_subtitle_switch_failed) { url -> serverPlayback.switchSubtitle(url, index) }
}

internal fun PlayerRuntimeController.clearServerSubtitle() {
    persistedTrackPreference = rememberedTrackPreference ?: persistedTrackPreference
    restartServerStream(R.string.servers_subtitle_switch_failed) { url -> serverPlayback.switchSubtitle(url, null) }
}

private fun PlayerRuntimeController.switchServerAudio(index: Int) {
    if (_uiState.value.serverAudioTracks.any { it.isSelected && it.index == index }) return
    restartServerStream(R.string.servers_audio_switch_failed) { url -> serverPlayback.switchAudio(url, index) }
}

private fun PlayerRuntimeController.restartServerStream(
    @StringRes failureMessage: Int,
    restart: suspend (String) -> ServerPlaybackSession?
) {
    val url = currentStreamUrl
    val positionMs = _playbackTimeline.value.currentPosition
    scope.launch {
        val session = restart(url)
        if (session == null) {
            Toast.makeText(context, context.getString(failureMessage), Toast.LENGTH_SHORT).show()
            return@launch
        }
        if (currentStreamUrl != url) {
            serverPlayback.stop(session.url)
            return@launch
        }
        restartServerSession(session, positionMs)
    }
}

private fun PlayerRuntimeController.restartServerSession(session: ServerPlaybackSession, positionMs: Long) {
    currentStreamUrl = session.url
    currentHeaders = session.headers
    currentStreamMimeType = PlayerMediaSourceFactory.inferMimeType(
        url = session.url,
        filename = null,
        responseHeaders = emptyMap()
    )
    currentStreamResponseHeaders = emptyMap()
    streamSubtitles = session.subtitles
    _uiState.update { it.copy(currentStreamUrl = session.url) }
    scheduleDeferredPlayerReinitialize(fromPositionMs = positionMs)
}

private fun PlayerRuntimeController.refreshServerTracks() {
    val audioTracks = serverPlayback.audioTracks(reportedServerUrl).map { it.toTrackInfo() }
    val subtitleTracks = serverPlayback.burnInSubtitles(reportedServerUrl).map { it.toTrackInfo() }
    _uiState.update {
        it.copy(
            serverAudioTracks = audioTracks,
            serverSubtitleTracks = subtitleTracks,
            isServerStream = reportedServerUrl != null
        )
    }
    if (!serverAudioChosenByUser) applyPreferredServerAudio(audioTracks)
}

private fun ServerTrack.toTrackInfo() = TrackInfo(index = index, name = label, language = language, isSelected = selected)

private fun PlayerRuntimeController.applyPreferredServerAudio(tracks: List<TrackInfo>) {
    val preferred = mpvPreferredAudioLanguages.firstNotNullOfOrNull { target ->
        tracks.firstOrNull { PlayerSubtitleUtils.matchesLanguageCode(it.language, target) }
    } ?: return
    if (!preferred.isSelected) switchServerAudio(preferred.index)
}

private const val SERVER_RESUME_TIMEOUT_MS = 2_000L
