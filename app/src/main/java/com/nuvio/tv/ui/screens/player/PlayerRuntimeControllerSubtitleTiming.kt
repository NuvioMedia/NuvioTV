package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import com.nuvio.tv.core.network.IPv4FirstDns
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private val subtitleAutoSyncHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(8000, TimeUnit.MILLISECONDS)
        .readTimeout(8000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L

/**
 * Alignment is CPU bound and runs while video is decoding. `Dispatchers.Default` is sized to the
 * core count, so on a low core TV box it would contend directly with playback. A single background
 * thread at minimum priority lets the scheduler shed it whenever the player needs the CPU; the work
 * is a one-shot user action, so the lost parallelism costs nothing (tracks were aligned
 * sequentially regardless).
 */
private val subtitleSyncDispatcher: CoroutineDispatcher by lazy {
    Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "subtitle-sync").apply {
            isDaemon = true
            priority = Thread.MIN_PRIORITY
        }
    }.asCoroutineDispatcher()
}

internal fun PlayerRuntimeController.showSubtitleTimingDialog() {
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = true,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleDelayOverlay = false,
            showMoreDialog = false,
            showSpeedDialog = false,
            showAudioOverlay = false,
            showControls = false,
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null
        )
    }
    maybeLoadSubtitleAutoSyncCues(force = false)
}

internal fun PlayerRuntimeController.dismissSubtitleTimingDialog() {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    _uiState.update { it.copy(showSubtitleTimingDialog = false, subtitleAutoSyncStatus = null) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.captureSubtitleAutoSyncTime() {
    val capturePositionMs = currentPlaybackPositionMs()?.coerceAtLeast(0L) ?: 0L
    _uiState.update {
        it.copy(
            subtitleAutoSyncCapturedVideoMs = capturePositionMs,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null
        )
    }
}

internal fun PlayerRuntimeController.applySubtitleAutoSyncCue(cueStartTimeMs: Long) {
    val capturePositionMs =
        _uiState.value.subtitleAutoSyncCapturedVideoMs ?: currentPlaybackPositionMs() ?: return
    val newDelayMs = (capturePositionMs - cueStartTimeMs - AUTO_SYNC_REACTION_COMPENSATION_MS)
        .toInt()
        .coerceIn(SUBTITLE_DELAY_MIN_MS, SUBTITLE_DELAY_MAX_MS)

    subtitleDelayUs.set(newDelayMs.toLong() * 1000L)
    _uiState.update {
        it.copy(
            subtitleDelayMs = newDelayMs,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = true,
            showControls = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(newDelayMs)
            ),
            subtitleAutoSyncError = null
        )
    }
    // Remember the delay so it survives to the next session (issue #1063).
    persistTrackPreference()
    refreshActiveSubtitleTrackAfterTimingChange()
    scheduleHideSubtitleDelayOverlay()
}

internal fun PlayerRuntimeController.reloadSubtitleAutoSyncCues() {
    maybeLoadSubtitleAutoSyncCues(force = true)
}

internal fun PlayerRuntimeController.automaticallySyncSubtitle() {
    if (isUsingMpvEngine()) {
        _uiState.update {
            it.copy(automaticSubtitleSyncMessage = context.getString(R.string.subtitle_automatic_sync_exoplayer_only))
        }
        return
    }
    val selectedSubtitle = _uiState.value.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(automaticSubtitleSyncMessage = context.getString(R.string.subtitle_auto_sync_select_addon_track))
        }
        return
    }
    automaticSubtitleSyncJob?.cancel()
    activeSubtitleReferenceScanner?.close()
    val streamUrlAtStart = currentStreamUrl
    val playerAtStart = _exoPlayer
    automaticSubtitleSyncJob = scope.launch {
        val syncJob = coroutineContext[Job]
        _uiState.update {
            it.copy(
                automaticSubtitleSyncRunning = true,
                automaticSubtitleSyncMessage = context.getString(R.string.subtitle_automatic_sync_analyzing)
            )
        }
        try {
            _uiState.update {
                it.copy(automaticSubtitleSyncMessage = context.getString(R.string.subtitle_automatic_sync_scanning_index))
            }
            val scanner = SubtitleReferenceScanner(
                context = context,
                url = currentStreamUrl,
                headers = currentHeaders,
                store = subtitleReferenceCueStore
            )
            activeSubtitleReferenceScanner = scanner
            val scanResult = try {
                scanner.scan()
            } finally {
                scanner.close()
                if (activeSubtitleReferenceScanner === scanner) activeSubtitleReferenceScanner = null
            }
            val referenceTracks = subtitleReferenceCueStore.snapshot()
                .filter { it.cues.size >= SubtitleReferenceCaptureStatus.MINIMUM_SYNC_CUES }
            if (referenceTracks.isEmpty()) {
                val message = when (scanResult) {
                    SubtitleReferenceScanResult.Unsupported -> R.string.subtitle_automatic_sync_scan_unsupported
                    SubtitleReferenceScanResult.IndexUnavailable -> R.string.subtitle_automatic_sync_index_unavailable
                    SubtitleReferenceScanResult.TimedOut -> R.string.subtitle_automatic_sync_scan_timed_out
                    is SubtitleReferenceScanResult.Indexed -> R.string.subtitle_automatic_sync_needs_dialogue
                }
                error(context.getString(message))
            }
            val targetDocument = withContext(subtitleSyncDispatcher) {
                SrtDocument.parse(downloadSubtitleBody(selectedSubtitle.url))
            }
            if (targetDocument.cues.size < 12) {
                error(context.getString(R.string.subtitle_automatic_sync_invalid_srt))
            }
            val plan = withContext(subtitleSyncDispatcher) {
                referenceTracks.mapNotNull { track ->
                    SubtitleRateAwareAligner.align(track.cues, targetDocument.cues)?.let { plan ->
                        plan to (plan.confidence - track.autoSyncTimingNoisePenalty())
                    }
                }.maxWithOrNull(
                    compareBy<Pair<SubtitleSyncPlan, Double>> { it.second }
                        .thenBy { it.first.model.matchedCueCount }
                )?.first
            } ?: error(context.getString(R.string.subtitle_automatic_sync_low_confidence))

            if (currentStreamUrl != streamUrlAtStart || _exoPlayer !== playerAtStart ||
                _uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedSubtitle.autoSyncTrackKey()) {
                return@launch
            }
            val rewritten = withContext(subtitleSyncDispatcher) { plan.rewrite(targetDocument) }
            if (rewritten.cues.isEmpty()) {
                error(context.getString(R.string.subtitle_automatic_sync_low_confidence))
            }
            val localUri = withContext(Dispatchers.IO) { subtitleSyncFileStore.write(rewritten) }
            if (currentStreamUrl != streamUrlAtStart || _exoPlayer !== playerAtStart ||
                _uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedSubtitle.autoSyncTrackKey()) {
                return@launch
            }
            synchronizedSubtitleOverride = SynchronizedSubtitleOverride(
                subtitleKey = addonSubtitleKey(selectedSubtitle),
                uri = localUri
            )
            subtitleDelayUs.set(0L)
            _uiState.update { it.copy(subtitleDelayMs = 0) }
            persistTrackPreference()
            reloadAddonSubtitlesForSync(selectedSubtitle)
            _uiState.update {
                it.copy(
                    automaticSubtitleSyncRunning = false,
                    automaticSubtitleSyncMessage = context.getString(
                        R.string.subtitle_automatic_sync_applied,
                        plan.model.segments.size,
                        (plan.confidence * 100).toInt()
                    )
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    automaticSubtitleSyncRunning = false,
                    automaticSubtitleSyncMessage = e.message
                        ?: context.getString(R.string.subtitle_automatic_sync_failed)
                )
            }
        } finally {
            if (automaticSubtitleSyncJob === syncJob) {
                automaticSubtitleSyncJob = null
                activeSubtitleReferenceScanner?.close()
                activeSubtitleReferenceScanner = null
                _uiState.update { it.copy(automaticSubtitleSyncRunning = false) }
            }
        }
    }
}

internal fun PlayerRuntimeController.resetSubtitleAutoSyncState(clearLoadedTrack: Boolean = true) {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    automaticSubtitleSyncJob?.cancel()
    automaticSubtitleSyncJob = null
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLoadedTrackKey = if (clearLoadedTrack) null else it.subtitleAutoSyncLoadedTrackKey,
            automaticSubtitleSyncMessage = null,
            automaticSubtitleSyncRunning = false
        )
    }
}

private fun PlayerRuntimeController.maybeLoadSubtitleAutoSyncCues(force: Boolean) {
    val selectedSubtitle = _uiState.value.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncCues = emptyList(),
                subtitleAutoSyncCapturedVideoMs = null,
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track),
                subtitleAutoSyncLoadedTrackKey = null
            )
        }
        return
    }

    val selectedTrackKey = selectedSubtitle.autoSyncTrackKey()
    val state = _uiState.value
    if (!force &&
        state.subtitleAutoSyncLoadedTrackKey == selectedTrackKey &&
        state.subtitleAutoSyncCues.isNotEmpty()
    ) {
        return
    }

    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = scope.launch {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = true,
                subtitleAutoSyncError = null,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncCues = if (force) emptyList() else it.subtitleAutoSyncCues,
                subtitleAutoSyncCapturedVideoMs = if (force) null else it.subtitleAutoSyncCapturedVideoMs,
                subtitleAutoSyncLoadedTrackKey = selectedTrackKey
            )
        }

        try {
            val rawSubtitleBody = downloadSubtitleBody(selectedSubtitle.url)
            val parsedCues = PlayerSubtitleCueParser.parseFromText(
                rawText = rawSubtitleBody,
                sourceUrl = selectedSubtitle.url
            )
                .filter { cue -> cue.text.isNotBlank() }

            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }

            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = parsedCues,
                    subtitleAutoSyncError = if (parsedCues.isEmpty()) {
                        context.getString(com.nuvio.tv.R.string.subtitle_timing_file_no_lines)
                    } else {
                        null
                    },
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (_uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() != selectedTrackKey) {
                return@launch
            }
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncCues = emptyList(),
                    subtitleAutoSyncError = e.message ?: context.getString(com.nuvio.tv.R.string.subtitle_timing_load_lines_failed),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        }
    }
}

private suspend fun PlayerRuntimeController.downloadSubtitleBody(url: String): String =
    withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        val subtitleOrigin = url.toHttpUrlOrNull()
        val streamOrigin = currentStreamUrl.toHttpUrlOrNull()
        if (subtitleOrigin != null && streamOrigin != null &&
            subtitleOrigin.isHttps && streamOrigin.isHttps &&
            subtitleOrigin.host.equals(streamOrigin.host, ignoreCase = true) &&
            subtitleOrigin.port == streamOrigin.port
        ) {
            currentHeaders
                .filterKeys { key -> key.equals("Referer", ignoreCase = true) || key.equals("Origin", ignoreCase = true) }
                .forEach { (key, value) -> requestBuilder.header(key, value) }
        }
        requestBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val request = requestBuilder.build()

        subtitleAutoSyncHttpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                error(context.getString(com.nuvio.tv.R.string.subtitle_download_failed_http, response.code))
            }
            val body = response.body?.string()
            if (body.isNullOrBlank()) {
                error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
            }
            body
        }
    }

private fun Subtitle.autoSyncTrackKey(): String = "$id|$url"

private fun SubtitleReferenceTrack.autoSyncTimingNoisePenalty(): Double {
    val normalizedName = name.lowercase()
    val noisyMarkers = listOf("sdh", "cc", "closed caption", "descriptive", "commentary", "hearing impaired")
    return if (noisyMarkers.any(normalizedName::contains)) 0.08 else 0.0
}

internal fun formatAutoSyncTimestamp(positionMs: Long): String {
    val totalSeconds = (positionMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

internal fun formatAutoSyncDelay(delayMs: Int): String {
    val sign = if (delayMs >= 0) "+" else "-"
    val absMs = kotlin.math.abs(delayMs)
    val seconds = absMs / 1000
    val millis = absMs % 1000
    return "$sign${seconds}.${millis.toString().padStart(3, '0')}s"
}
