package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

private val subtitleAutoSyncHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        // Sidecar + auto-sync both use this client; keep timeouts generous for flaky hosts.
        .connectTimeout(12_000, TimeUnit.MILLISECONDS)
        .readTimeout(15_000, TimeUnit.MILLISECONDS)
        .callTimeout(25_000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private const val SUBTITLE_DOWNLOAD_MAX_ATTEMPTS = 3
private const val SUBTITLE_DOWNLOAD_RETRY_DELAY_MS = 350L

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L

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

internal fun PlayerRuntimeController.resetSubtitleAutoSyncState(clearLoadedTrack: Boolean = true) {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLoadedTrackKey = if (clearLoadedTrack) null else it.subtitleAutoSyncLoadedTrackKey
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
            val rawSubtitleBody = downloadSubtitleBody(selectedSubtitle.url, selectedSubtitle.lang)
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

/**
 * Downloads a remote subtitle body for sidecar rendering / auto-sync.
 *
 * Video stream headers (Authorization, Cookie, custom Host, …) are only forwarded when the
 * subtitle URL shares the same host as the active stream. Forwarding debrid/CDN headers to
 * OpenSubtitles-style hosts is a common cause of intermittent HTTP 4xx / empty bodies.
 */
internal suspend fun PlayerRuntimeController.downloadSubtitleBody(url: String, languageHint: String? = null): String =
    withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(SUBTITLE_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext executeSubtitleDownload(url, languageHint)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastError = e
                if (attempt < SUBTITLE_DOWNLOAD_MAX_ATTEMPTS - 1) {
                    delay(SUBTITLE_DOWNLOAD_RETRY_DELAY_MS * (attempt + 1))
                }
            }
        }
        throw lastError ?: IllegalStateException("Subtitle download failed")
    }

private fun PlayerRuntimeController.executeSubtitleDownload(url: String, languageHint: String? = null): String {
    val requestBuilder = Request.Builder().url(url)
    val subtitleHost = runCatching { android.net.Uri.parse(url).host }.getOrNull()
    val streamHost = runCatching { android.net.Uri.parse(currentStreamUrl).host }.getOrNull()
    val sameHost = !subtitleHost.isNullOrBlank() &&
        subtitleHost.equals(streamHost, ignoreCase = true)

    if (sameHost) {
        currentHeaders
            .filterKeys { key ->
                // Never forward hop-by-hop / range / host — they break foreign or CDN edges.
                !key.equals("Range", ignoreCase = true) &&
                    !key.equals("Host", ignoreCase = true) &&
                    !key.equals("Connection", ignoreCase = true) &&
                    !key.equals("Transfer-Encoding", ignoreCase = true)
            }
            .forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
    }

    requestBuilder.header(
        "User-Agent",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
            "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    )
    requestBuilder.header("Accept", "text/plain, text/vtt, application/x-subrip, */*")

    subtitleAutoSyncHttpClient.newCall(requestBuilder.build()).execute().use { response ->
        if (!response.isSuccessful) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_failed_http, response.code))
        }
        val bodyBytes = response.body?.bytes()
            ?: error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        if (bodyBytes.isEmpty()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        val body = SubtitleCharsetDetector.decode(bodyBytes, languageHint = languageHint)
        if (body.isBlank()) {
            error(context.getString(com.nuvio.tv.R.string.subtitle_download_empty_content))
        }
        return body
    }
}

private fun Subtitle.autoSyncTrackKey(): String = "$id|$url"

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
