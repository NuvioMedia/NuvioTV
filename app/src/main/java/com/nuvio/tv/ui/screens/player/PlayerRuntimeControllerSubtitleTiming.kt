package com.nuvio.tv.ui.screens.player

import android.graphics.Bitmap
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.data.local.SubtitleAiProvider
import com.nuvio.tv.data.local.subtitleAiModelForProvider
import androidx.media3.common.text.CueGroup
import androidx.media3.common.text.Cue
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.nuvio.tv.data.local.InternalPlayerEngine
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import com.nuvio.tv.core.network.IPv4FirstDns
import kotlinx.coroutines.Job
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.math.abs

private val subtitleAutoSyncHttpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .dns(IPv4FirstDns())
        .connectTimeout(20000, TimeUnit.MILLISECONDS)
        .readTimeout(20000, TimeUnit.MILLISECONDS)
        .writeTimeout(20000, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()
}

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L

// Number of consecutive built-in lines sent to the LLM as the source window (see
// buildSubtitleAutoSyncPrompt) — also the minimum the cue buffer must reach before matching.
internal const val AUTO_SYNC_SOURCE_LINE_COUNT = 5

// Some movies/episodes open with several dialogue-free minutes, so give calibration plenty of
// room to catch the first built-in lines rather than timing out prematurely.
private const val AUTO_SYNC_CUE_GATHER_TIMEOUT_MS = 600_000L

// Retry attempts (beyond the first) only exist to add more independent data points on top of an
// already-successful first match, so they don't need to wait nearly as long for dialogue.
private const val AUTO_SYNC_RETRY_CUE_GATHER_TIMEOUT_MS = 30_000L

// Total match attempts (1 initial + up to 2 retries) and the target number of pooled per-pair
// offsets across all attempts before we stop retrying and compute the final robust mean (see
// robustMeanOfLongs).
private const val AUTO_SYNC_MAX_ATTEMPTS = 3
private const val AUTO_SYNC_TARGET_POOLED_OFFSETS = 3

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
    // A manual cue apply supersedes any previously-detected drift rate — including one still
    // being calibrated in the background.
    subtitleDelayRateUsPerUs.set(0.0)
    subtitleDelayRateAnchorPositionUs.set(SUBTITLE_DRIFT_ANCHOR_PENDING_US)
    cancelSubtitleDriftCalibration()
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

internal fun PlayerRuntimeController.recordSubtitleAutoSyncSourceCueGroup(cueGroup: CueGroup) {
    if (cueGroup.cues.isEmpty()) return
    if (_uiState.value.selectedAddonSubtitle != null && !subtitleAutoSyncTemporarilyShowingInternal) {
        // ExoPlayer only renders one selected text track at a time. Once an addon subtitle is
        // selected it becomes the one actually producing CueGroups, so recording here would
        // silently overwrite the "built-in reference" buffer with addon-language lines — unless
        // Auto Sync has deliberately overridden playback back to the built-in track to gather
        // fresh reference cues (see beginTemporaryInternalCueGathering).
        return
    }
    val presentationTimeMs = (cueGroup.presentationTimeUs / 1000L).coerceAtLeast(0L)
    val rawTexts = cueGroup.cues
        .mapNotNull { cue -> cue.text?.toString()?.trim()?.takeIf { it.isNotEmpty() } }
    if (rawTexts.isNotEmpty()) {
        subtitleAutoSyncSawNonTextCues = false
        // Music/SFX annotations (e.g. "♪ ... ♪", "[Music]") are common on built-in SDH tracks but
        // rarely present in addon subtitles, so they'd never find a match — skip them silently
        // rather than treating this as a non-text (image-based) track or buffering a useless line.
        val validCues = rawTexts.filterNot(::isNonDialogueMusicCue)
        if (validCues.isEmpty()) return
        appendSubtitleAutoSyncCue(presentationTimeMs, validCues.first())
        return
    }

    // resolveInternalSubtitleTrackIndexForAutoSync always prefers a text-based built-in track
    // when one exists, so a bitmap-only cue group here means this file truly has no text track —
    // OCR the image (PGS/DVB) cue as a last-resort fallback instead of giving up immediately.
    val bitmap = cueGroup.cues.firstNotNullOfOrNull { it.bitmap }
    if (bitmap != null) {
        subtitleAutoSyncSawNonTextCues = false
        scope.launch(Dispatchers.Default) {
            val recognizedText = runCatching { recognizeSubtitleBitmapText(bitmap) }
                .getOrNull()
                ?.trim()
                ?.takeIf { it.isNotEmpty() && !isNonDialogueMusicCue(it) }
                ?: return@launch
            withContext(Dispatchers.Main) {
                appendSubtitleAutoSyncCue(presentationTimeMs, recognizedText)
            }
        }
        return
    }

    if (!subtitleAutoSyncSawNonTextCues) {
        subtitleAutoSyncSawNonTextCues = true
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_non_text_track)
            )
        }
    }
}

private fun PlayerRuntimeController.appendSubtitleAutoSyncCue(presentationTimeMs: Long, text: String) {
    val lastCueTimeMs = subtitleAutoSyncSourceCueBuffer.lastOrNull()?.startTimeMs
    if (lastCueTimeMs != null && kotlin.math.abs(presentationTimeMs - lastCueTimeMs) > 90_000L) {
        // Seek/jump detected: discard stale cues so matching uses the new timeline region.
        subtitleAutoSyncSourceCueBuffer.clear()
    }
    // Live-captured cues only carry a presentation instant, not a real duration.
    subtitleAutoSyncSourceCueBuffer += SubtitleSyncCue(
        startTimeMs = presentationTimeMs,
        endTimeMs = presentationTimeMs,
        text = text
    )
    while (subtitleAutoSyncSourceCueBuffer.size > 20) {
        subtitleAutoSyncSourceCueBuffer.removeAt(0)
    }
    _uiState.update {
        it.copy(subtitleAutoSyncCues = subtitleAutoSyncSourceCueBuffer.toList())
    }
}

internal suspend fun recognizeSubtitleBitmapText(bitmap: Bitmap): String? = suspendCancellableCoroutine { cont ->
    val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    val image = InputImage.fromBitmap(bitmap, 0)
    recognizer.process(image)
        .addOnSuccessListener { visionText -> if (cont.isActive) cont.resume(visionText.text) }
        .addOnFailureListener { if (cont.isActive) cont.resume(null) }
        .addOnCompleteListener { recognizer.close() }
}

internal fun PlayerRuntimeController.clearSubtitleAutoSyncSourceCueBuffer() {
    subtitleAutoSyncJob?.cancel()
    subtitleAutoSyncJob = null
    subtitleAutoSyncSawNonTextCues = false
    subtitleAutoSyncSourceCueBuffer.clear()
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLastLlmRequest = null,
            subtitleAutoSyncLastLlmResponse = null,
            subtitleAutoSyncLoadedTrackKey = null
        )
    }
}

internal fun PlayerRuntimeController.clearSubtitleAutoSyncSessionMarkers() {
    subtitleAutoSyncCompletedSessionKeys.clear()
    subtitleAutoSyncInFlightSessionKey = null
}

internal fun PlayerRuntimeController.tryStartSubtitleAutoSyncForSelectedAddon(
    subtitle: Subtitle,
    force: Boolean = false
) {
    if (isUsingMpvEngine()) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = "Auto Sync is unavailable with MPV. Switch to ExoPlayer internal engine."
            )
        }
        return
    }
    val internalTrackIndex = resolveInternalSubtitleTrackIndexForAutoSync() ?: run {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = "No built-in subtitle tracks are available in this file."
            )
        }
        return
    }
    val internalTrack = _uiState.value.subtitleTracks.getOrNull(internalTrackIndex) ?: run {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = "No built-in subtitle tracks are available in this file."
            )
        }
        return
    }
    val sourceKey = internalTrack.trackId
        ?: "ui_text_${internalTrackIndex}_${internalTrack.language ?: "und"}_${internalTrack.name}"
    if (!_uiState.value.subtitleAutoSyncLoading) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = true,
                subtitleAutoSyncError = null,
                subtitleAutoSyncStatus = null
            )
        }
    }

    val sessionKey = "$sourceKey|${subtitle.url}"
    if (sessionKey == subtitleAutoSyncInFlightSessionKey) {
        // A request for this exact source track + addon subtitle is already running; let it finish.
        return
    }
    if (sessionKey in subtitleAutoSyncCompletedSessionKeys) {
        if (!force) {
            _uiState.update { it.copy(subtitleAutoSyncLoading = false) }
            return
        }
        // Explicit manual re-run: forget the previous completion so it can run again.
        subtitleAutoSyncCompletedSessionKeys -= sessionKey
    }
    subtitleAutoSyncInFlightSessionKey = sessionKey

    subtitleAutoSyncJob?.cancel()
    subtitleAutoSyncJob = scope.launch(CoroutineExceptionHandler { _, throwable ->
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncError = throwable.message ?: context.getString(R.string.subtitle_timing_load_lines_failed)
            )
        }
        subtitleAutoSyncInFlightSessionKey = null
    }) {
        var gatheringInternally = false
        try {
            val settings = playerSettingsDataStore.playerSettings.first()
            // subtitleAiAutoSelect only gates automatic triggering ("as soon as the addon track
            // is selected"); an explicit manual Apply click should still run regardless of it.
            if (!settings.autoSyncExternalSubtitles || (!force && !settings.subtitleAiAutoSelect)) {
                _uiState.update {
                    it.copy(
                        subtitleAutoSyncLoading = false,
                        subtitleAutoSyncStatus = "Auto Sync skipped: enable both 'Auto-Sync Addon Subtitles' and 'Auto-Select AI Sync'.",
                        subtitleAutoSyncError = null
                    )
                }
                subtitleAutoSyncInFlightSessionKey = null
                return@launch
            }

            // Every run starts from a clean slate: cues gathered below must be directly
            // comparable to the addon file's raw timestamps (see runSubtitleAutoSyncRequest),
            // and the user should see built-in captions in true sync while calibrating rather
            // than skewed by whatever delay a previous run left in place.
            subtitleDelayUs.set(0L)
            subtitleDelayRateUsPerUs.set(0.0)
            subtitleDelayRateAnchorPositionUs.set(SUBTITLE_DRIFT_ANCHOR_PENDING_US)
            // A drift calibration from a PREVIOUS run may still be running in the background
            // (its gather timeout alone can take minutes) - without this, it can finish AFTER
            // this fresh reset and silently reapply a stale/now-mismatched rate on top of it.
            cancelSubtitleDriftCalibration()
            _uiState.update {
                it.copy(
                    subtitleDelayMs = 0,
                    subtitleAutoSyncLastLlmRequest = null,
                    subtitleAutoSyncLastLlmResponse = null,
                    // Otherwise the "Show LLM Payload" dialog and the drift status line keep
                    // showing a previous run's stale content mixed in with (or instead of) this
                    // run's, since these two fields were previously only cleared on a track/addon
                    // change, not on every fresh Apply Auto Sync.
                    subtitleDriftCorrectionInfo = null,
                    subtitleDriftDebugTrace = null
                )
            }

            val pooledOffsetsMs = mutableListOf<Long>()
            var lastResult: SubtitleAutoSyncResult? = null
            var attemptsUsed = 0

            for (attempt in 1..AUTO_SYNC_MAX_ATTEMPTS) {
                attemptsUsed = attempt
                val needsGather = attempt > 1 || force ||
                    subtitleAutoSyncSourceCueBuffer.size < AUTO_SYNC_SOURCE_LINE_COUNT
                if (needsGather) {
                    // Cues only accumulate while the built-in track is actually rendering (see
                    // the guard in recordSubtitleAutoSyncSourceCueGroup). Rather than asking the
                    // user to manually flip tracks, silently override playback to the built-in
                    // track for a moment to collect fresh reference lines, then restore the addon
                    // automatically — both overrides are cheap track-selection swaps on the
                    // already-buffered media, not a new download/decode of the video.
                    if (attempt > 1 || force) {
                        // A retry or a manual re-run should reflect where playback is NOW, not
                        // whatever cues happened to still be buffered from an earlier point.
                        subtitleAutoSyncSourceCueBuffer.clear()
                    }
                    _uiState.update {
                        it.copy(
                            subtitleAutoSyncLoading = true,
                            subtitleAutoSyncStatus = if (attempt == 1) {
                                "Calibrating with built-in subtitle lines (attempt $attempt of $AUTO_SYNC_MAX_ATTEMPTS; captions will show the built-in language until this finishes)..."
                            } else {
                                "Refining sync with more built-in lines (attempt $attempt of $AUTO_SYNC_MAX_ATTEMPTS)..."
                            },
                            subtitleAutoSyncError = null
                        )
                    }
                    if (!gatheringInternally) {
                        gatheringInternally = beginTemporaryInternalCueGathering(internalTrackIndex)
                        if (!gatheringInternally) {
                            _uiState.update {
                                it.copy(
                                    subtitleAutoSyncLoading = false,
                                    subtitleAutoSyncStatus = null,
                                    subtitleAutoSyncError = "Couldn't switch to the built-in subtitle track to calibrate. Try again."
                                )
                            }
                            subtitleAutoSyncInFlightSessionKey = null
                            return@launch
                        }
                    }
                    val gatherTimeoutMs = if (attempt == 1) {
                        AUTO_SYNC_CUE_GATHER_TIMEOUT_MS
                    } else {
                        AUTO_SYNC_RETRY_CUE_GATHER_TIMEOUT_MS
                    }
                    val gathered = waitForSubtitleAutoSyncCues(
                        minCount = AUTO_SYNC_SOURCE_LINE_COUNT,
                        timeoutMs = gatherTimeoutMs
                    )
                    if (!gathered) {
                        if (pooledOffsetsMs.isEmpty()) {
                            _uiState.update {
                                it.copy(
                                    subtitleAutoSyncLoading = false,
                                    subtitleAutoSyncStatus = null,
                                    subtitleAutoSyncError = "Not enough built-in subtitle dialogue right now to calibrate. Try again in a moment."
                                )
                            }
                            subtitleAutoSyncInFlightSessionKey = null
                            return@launch
                        }
                        // A retry timing out is fine to give up on — we already have at least one
                        // result from an earlier attempt to fall back on.
                        break
                    }
                }

                val internalCues = subtitleAutoSyncSourceCueBuffer.takeLast(AUTO_SYNC_SOURCE_LINE_COUNT)
                if (internalCues.isEmpty()) {
                    if (pooledOffsetsMs.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                subtitleAutoSyncLoading = false,
                                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track)
                            )
                        }
                        subtitleAutoSyncInFlightSessionKey = null
                        return@launch
                    }
                    break
                }

                _uiState.update {
                    it.copy(
                        subtitleAutoSyncLoading = true,
                        subtitleAutoSyncStatus = "Downloading addon subtitle lines (attempt $attempt of $AUTO_SYNC_MAX_ATTEMPTS)...",
                        subtitleAutoSyncError = null
                    )
                }
                val result = runSubtitleAutoSyncRequest(subtitle, internalCues, attempt, AUTO_SYNC_MAX_ATTEMPTS)
                pooledOffsetsMs += result.offsetsMs
                lastResult = result

                // Enough independent data points pooled across attempts — stop retrying.
                if (pooledOffsetsMs.size >= AUTO_SYNC_TARGET_POOLED_OFFSETS) break
            }

            if (lastResult == null || pooledOffsetsMs.isEmpty()) {
                _uiState.update {
                    it.copy(
                        subtitleAutoSyncLoading = false,
                        subtitleAutoSyncError = "AI returned no subtitle line matches after all attempts."
                    )
                }
                subtitleAutoSyncInFlightSessionKey = null
                return@launch
            }

            // pooledOffsetsMs values are already the full delay needed (source/target cue
            // timestamps are both raw, delay-independent values — see
            // runSubtitleAutoSyncRequest), not deltas to add on top of whatever delay is already
            // set. Adding it would make repeated Auto Sync runs accumulate/drift further off with
            // every press instead of converging.
            val newDelayMs = robustMeanOfLongs(pooledOffsetsMs)
                .coerceIn(SUBTITLE_DELAY_MIN_MS.toLong(), SUBTITLE_DELAY_MAX_MS.toLong())
            subtitleDelayUs.set(newDelayMs * 1000L)
            // This is anchor1 for a possible later drift calibration (Phase 3) - no rate is known
            // yet, just the single-point delay and the position it was measured at.
            subtitleDelayRateUsPerUs.set(0.0)
            val anchor1PositionMs = currentPlaybackPositionMs() ?: 0L
            subtitleDelayRateAnchorPositionUs.set(SUBTITLE_DRIFT_ANCHOR_PENDING_US)
            val matchWord = if (pooledOffsetsMs.size == 1) "match" else "matches"
            val attemptWord = if (attemptsUsed == 1) "attempt" else "attempts"
            _uiState.update {
                it.copy(
                    subtitleDelayMs = (subtitleDelayUs.get() / 1000L).toInt(),
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncStatus = context.getString(
                        R.string.subtitle_auto_sync_applied,
                        formatAutoSyncDelay((subtitleDelayUs.get() / 1000L).toInt())
                    ) + " (from ${pooledOffsetsMs.size} $matchWord across $attemptsUsed $attemptWord)",
                    subtitleAutoSyncError = null
                )
            }
            persistTrackPreference()
            refreshActiveSubtitleTrackAfterTimingChange()
            subtitleAutoSyncCompletedSessionKeys += sessionKey
            // Best-effort second anchor to detect/correct a frame-rate-style linear drift on top
            // of this single-point delay - entirely background, never blocks or affects the UI
            // state updated above.
            maybeStartSubtitleDriftCalibration(
                subtitle = subtitle,
                internalTrack = internalTrack,
                anchor1PositionMs = anchor1PositionMs,
                anchor1DelayMs = newDelayMs
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncStatus = null,
                    subtitleAutoSyncError = "Auto Sync timed out while downloading or matching subtitles."
                )
            }
        } catch (e: Exception) {
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = false,
                    subtitleAutoSyncError = e.message ?: context.getString(R.string.subtitle_timing_load_lines_failed)
                )
            }
        } finally {
            if (gatheringInternally) {
                endTemporaryInternalCueGathering(subtitle)
            }
            subtitleAutoSyncInFlightSessionKey = null
        }
    }
}

private fun PlayerRuntimeController.beginTemporaryInternalCueGathering(trackIndex: Int): Boolean {
    // Reuses the same path a manual subtitle-track selection takes (matches by UI list index,
    // not a raw ExoPlayer track-group scan), so it reliably lands on the track the subtitle
    // picker itself would show as that index — not an arbitrary/sparse track.
    selectSubtitleTrack(trackIndex)
    subtitleAutoSyncTemporarilyShowingInternal = true
    return true
}

private fun PlayerRuntimeController.endTemporaryInternalCueGathering(addonSubtitle: Subtitle) {
    subtitleAutoSyncTemporarilyShowingInternal = false
    val restored = applyAddonSubtitleOverride(buildAddonSubtitleTrackId(addonSubtitle))
    if (!restored) {
        applyAddonSubtitleOverrideByLanguage(PlayerSubtitleUtils.normalizeLanguageCode(addonSubtitle.lang))
    }
}

private suspend fun PlayerRuntimeController.waitForSubtitleAutoSyncCues(
    minCount: Int,
    timeoutMs: Long
): Boolean {
    val pollIntervalMs = 250L
    var waitedMs = 0L
    while (subtitleAutoSyncSourceCueBuffer.size < minCount && waitedMs < timeoutMs) {
        delay(pollIntervalMs)
        waitedMs += pollIntervalMs
    }
    return subtitleAutoSyncSourceCueBuffer.size >= minCount
}

internal data class SubtitleAutoSyncResult(
    val offsetsMs: List<Long>,
    val sourceIndex: Int,
    val targetIndex: Int,
    val sourceLine: String,
    val targetLine: String
)

private data class SubtitleAutoSyncLlmResponse(
    val json: JSONObject,
    val rawText: String
)

internal suspend fun PlayerRuntimeController.runSubtitleAutoSyncRequest(
    subtitle: Subtitle,
    internalCues: List<SubtitleSyncCue>,
    attempt: Int,
    maxAttempts: Int,
    referenceDelayCompensationMsOverride: Long? = null
): SubtitleAutoSyncResult = withContext(Dispatchers.IO) {
    val settings = playerSettingsDataStore.playerSettings.first()
    val provider = SubtitleAiProvider.fromValue(settings.subtitleAiProvider)
    val model = subtitleAiModelForProvider(provider)
    val apiKey = when (provider) {
        SubtitleAiProvider.GROQ -> settings.subtitleAiGroqKey.trim()
        SubtitleAiProvider.GEMINI -> settings.subtitleAiGeminiKey.trim()
    }
    if (apiKey.isBlank()) {
        val providerName = if (provider == SubtitleAiProvider.GROQ) "Groq" else "Gemini"
        error("$providerName API key is missing. Set it in Settings > Playback > Subtitles.")
    }

    val allAddonCues = loadAddonCuesCached(subtitle)
    if (allAddonCues.isEmpty()) {
        error(context.getString(R.string.subtitle_timing_file_no_lines))
    }

    val referenceCue = internalCues.last()
    // referenceCue.startTimeMs was captured while subtitleDelayUs was already applied to the
    // text renderer's position (see SubtitleOffsetRenderer), so it lags true video time by the
    // currently active delay. Shift it back before comparing to the addon file's raw timestamps.
    // A cue captured by a delay-agnostic pipeline (e.g. the secondary drift-calibration player,
    // which never applies subtitleDelayUs at all) must pass 0L explicitly via the override.
    val currentDelayMsAtCapture = referenceDelayCompensationMsOverride ?: (subtitleDelayUs.get() / 1000L)
    val approxTrueTimeMs = referenceCue.startTimeMs + currentDelayMsAtCapture
    val nearestAddonIndex = allAddonCues.indices.minByOrNull { index ->
        kotlin.math.abs(allAddonCues[index].startTimeMs - approxTrueTimeMs)
    } ?: 0
    val radius = 12
    val startIndex = (nearestAddonIndex - radius).coerceAtLeast(0)
    val endIndex = (nearestAddonIndex + radius).coerceAtMost(allAddonCues.lastIndex)
    val addonCues = allAddonCues.subList(startIndex, endIndex + 1)
    if (addonCues.isEmpty()) {
        error("No addon subtitle lines were found near the current playback position.")
    }

    _uiState.update {
        it.copy(
            subtitleAutoSyncLoading = true,
            subtitleAutoSyncStatus = "Matching built-in lines to addon subtitle with AI (attempt $attempt of $maxAttempts)...",
            subtitleAutoSyncError = null
        )
    }

    val sourceLinesFormatted = internalCues.mapIndexed { index, cue ->
        "[$index] ${JSONObject.quote(cue.text)}"
    }.joinToString(separator = "\n")
    val targetLinesFormatted = addonCues.mapIndexed { index, cue ->
        "[$index] ${JSONObject.quote(cue.text)}"
    }.joinToString(separator = "\n")
    val prompt = buildSubtitleAutoSyncPrompt(sourceLinesFormatted, targetLinesFormatted)
    val requestDebug = buildString {
        appendLine("=== Attempt $attempt of $maxAttempts ===")
        appendLine("provider=$provider")
        appendLine("model=$model")
        appendLine("addonWindowStart=$startIndex addonWindowEnd=$endIndex size=${addonCues.size}")
        appendLine("source_lines:")
        appendLine(sourceLinesFormatted)
        appendLine("target_lines:")
        appendLine(targetLinesFormatted)
        appendLine("prompt:")
        append(prompt)
    }
    _uiState.update {
        it.copy(
            subtitleAutoSyncLastLlmRequest = listOfNotNull(it.subtitleAutoSyncLastLlmRequest, requestDebug)
                .joinToString(separator = "\n\n")
        )
    }

    val llmResponse = when (provider) {
        SubtitleAiProvider.GROQ -> requestGroqSubtitleMatch(model, apiKey, prompt)
        SubtitleAiProvider.GEMINI -> requestGeminiSubtitleMatch(model, apiKey, prompt)
    } ?: error("AI returned an empty or invalid response while matching subtitles.")

    val pairsArray = llmResponse.json.optJSONArray("pairs")
    val validPairs = (0 until (pairsArray?.length() ?: 0)).mapNotNull { i ->
        val pairObj = pairsArray?.optJSONObject(i) ?: return@mapNotNull null
        val sIndex = pairObj.optInt("source_index", Int.MIN_VALUE)
        val tIndex = pairObj.optInt("target_index", Int.MIN_VALUE)
        if (sIndex !in internalCues.indices || tIndex !in addonCues.indices) return@mapNotNull null
        // subtitleDelayUs lags the text renderer's position behind real time
        // (adjustedPositionUs = positionUs - delay, see SubtitleOffsetRenderer), so aligning the
        // addon cue's raw file time to the source cue's true moment requires
        // delay += source - target, not the reverse.
        val pairOffsetMs = internalCues[sIndex].startTimeMs - addonCues[tIndex].startTimeMs
        Triple(sIndex, tIndex, pairOffsetMs)
    }
    if (validPairs.isEmpty()) {
        val failureBlock = "=== Attempt $attempt of $maxAttempts (no in-range matches) ===\n${llmResponse.rawText}"
        _uiState.update {
            it.copy(
                subtitleAutoSyncLastLlmResponse = listOfNotNull(it.subtitleAutoSyncLastLlmResponse, failureBlock)
                    .joinToString(separator = "\n\n")
            )
        }
        error("AI returned no in-range subtitle line matches for the subtitle window.")
    }

    // Per-call diagnostic only — the value that actually decides the applied delay is the
    // robust (outlier-filtered) mean pooled across ALL attempts/anchors, computed later by the
    // caller via robustMeanOfLongs, not this single call's own pairs.
    val thisCallRobustMeanOffsetMs = robustMeanOfLongs(validPairs.map { it.third })
    val anchor = validPairs.first()
    val anchorSourceCue = internalCues[anchor.first]
    val anchorTargetCue = addonCues[anchor.second]
    val diagnostics = buildString {
        appendLine("=== Attempt $attempt of $maxAttempts ===")
        appendLine("parsed pairs (source_index,target_index)=offset_ms: " +
            validPairs.joinToString(", ") { "(${it.first},${it.second})=${it.third}" })
        appendLine("this_call_robust_mean_offset_ms=$thisCallRobustMeanOffsetMs")
        append(llmResponse.rawText)
    }
    _uiState.update {
        it.copy(
            subtitleAutoSyncLastLlmResponse = listOfNotNull(it.subtitleAutoSyncLastLlmResponse, diagnostics)
                .joinToString(separator = "\n\n")
        )
    }

    SubtitleAutoSyncResult(
        offsetsMs = validPairs.map { it.third },
        sourceIndex = anchor.first,
        targetIndex = anchor.second,
        sourceLine = anchorSourceCue.text,
        targetLine = anchorTargetCue.text
    )
}

internal fun medianOfLongs(values: List<Long>): Long {
    val sorted = values.sorted()
    val n = sorted.size
    return if (n % 2 == 1) {
        sorted[n / 2]
    } else {
        Math.round((sorted[n / 2 - 1] + sorted[n / 2]) / 2.0)
    }
}

// Pairs deviating from the median by more than this are treated as gross mismatches (bad
// OCR/LLM matches), not genuine data points, and excluded before averaging — see
// robustMeanOfLongs. Comfortably above normal reaction-time/frame-boundary noise, well below a
// plausible real offset difference.
private const val AUTO_SYNC_OUTLIER_THRESHOLD_MS = 450L

// Median alone is robust but wastes information (ignores magnitude among the "good" points),
// giving it more variance than necessary when most pairs actually agree. A plain mean is more
// precise on clean data but has zero tolerance for a single gross mismatch (a known failure mode
// here — occasional OCR misreads). This combines both: use the median purely as a reference to
// reject gross outliers, then average whatever remains for a tighter estimate on the "good" data.
internal fun robustMeanOfLongs(values: List<Long>): Long {
    if (values.isEmpty()) return 0L
    if (values.size <= 2) return medianOfLongs(values)
    val median = medianOfLongs(values)
    val inliers = values.filter { abs(it - median) <= AUTO_SYNC_OUTLIER_THRESHOLD_MS }
    val effective = inliers.ifEmpty { values }
    return Math.round(effective.sum().toDouble() / effective.size)
}

private val MUSIC_NOTE_CHARS = charArrayOf('\u266A', '\u266B', '\u266C', '\u2669')
private val NON_DIALOGUE_BRACKET_KEYWORDS = listOf("music", "song", "theme", "instrumental", "singing", "humming")

internal fun isNonDialogueMusicCue(text: String): Boolean {
    if (text.any { it in MUSIC_NOTE_CHARS }) return true
    val bracketed = (text.startsWith("[") && text.endsWith("]")) ||
        (text.startsWith("(") && text.endsWith(")"))
    if (!bracketed) return false
    val inner = text.substring(1, text.length - 1).lowercase()
    return NON_DIALOGUE_BRACKET_KEYWORDS.any { inner.contains(it) }
}

private fun buildSubtitleAutoSyncPrompt(
    sourceLinesFormattedWithIndices: String,
    targetLinesFormattedWithIndices: String
): String {
    return """
        System:
        You are a subtitle synchronization algorithm. Your job is to find semantic matches between source subtitle lines and a target subtitle list.
        You must ignore translator credits, sync warnings, or empty lines.
        Prefer lines with distinctive, specific wording (names, numbers, uncommon phrases) over short generic lines like "Yes." or "What?", since generic lines are ambiguous to match.
        Start with your single most confident source/target match. Then check the OTHER source lines too: for each one you can ALSO confidently match to a specific target line, include it as a separate pair. Only include pairs you genuinely believe are correct — it's fine to return just 1 pair, or up to all 5, whatever you can actually verify. Do not guess extra pairs just to fill them in.
        Note that source and target subtitles are not always split 1-to-1 (one source line can correspond to two target lines or vice versa), so do not assume matches follow any fixed pattern (e.g. do not just add a constant offset to indices) — match each line independently based on its own meaning.
        Output ONLY a valid JSON object with a single key "pairs": an array of objects, each with integer keys "source_index" and "target_index".
        Do not output markdown, explanations, or any other text.

        User:
        <source_lines> are consecutive subtitle lines from the built-in track, in chronological order.
        <target_lines> are subtitle lines from the addon file, also in chronological order.
        Find as many confident source/target matches as you can, starting with your best one.

        <source_lines>
        $sourceLinesFormattedWithIndices
        </source_lines>

        <target_lines>
        $targetLinesFormattedWithIndices
        </target_lines>

        Expected JSON output format:
        {"pairs": [{"source_index": <integer>, "target_index": <integer>}, ...]}
    """.trimIndent()
}

private suspend fun PlayerRuntimeController.requestGroqSubtitleMatch(
    model: String,
    apiKey: String,
    prompt: String
): SubtitleAutoSyncLlmResponse? = withContext(Dispatchers.IO) {
    val requestJson = JSONObject().apply {
        put("model", model)
        put("temperature", 0.0)
        put("response_format", JSONObject().put("type", "json_object"))
        put("messages", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "system")
                put("content", "You are a subtitle synchronization algorithm. Output only JSON with a \"pairs\" array of source_index/target_index matches.")
            })
            put(JSONObject().apply {
                put("role", "user")
                put("content", prompt)
            })
        })
    }
    val request = Request.Builder()
        .url("https://api.groq.com/openai/v1/chat/completions")
        .header("Authorization", "Bearer $apiKey")
        .header("Content-Type", "application/json")
        .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    subtitleAutoSyncHttpClient.newCall(request).execute().use { response ->
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val snippet = bodyText.take(220)
            error("Groq API request failed (${response.code}): $snippet")
        }
        val content = JSONObject(bodyText)
            .optJSONArray("choices")
            ?.optJSONObject(0)
            ?.optJSONObject("message")
            ?.optString("content")
            .orEmpty()
        if (content.isBlank()) return@withContext null
        val parsed = runCatching { JSONObject(content) }.getOrNull() ?: return@withContext null
        SubtitleAutoSyncLlmResponse(json = parsed, rawText = content)
    }
}

private suspend fun PlayerRuntimeController.requestGeminiSubtitleMatch(
    model: String,
    apiKey: String,
    prompt: String
): SubtitleAutoSyncLlmResponse? = withContext(Dispatchers.IO) {
    val requestJson = JSONObject().apply {
        put("generationConfig", JSONObject().apply {
            put("temperature", 0.0)
            put("responseMimeType", "application/json")
        })
        put("contents", JSONArray().apply {
            put(JSONObject().apply {
                put("role", "user")
                put("parts", JSONArray().put(JSONObject().put("text", prompt)))
            })
        })
    }
    val request = Request.Builder()
        .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
        .header("Content-Type", "application/json")
        .post(requestJson.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    subtitleAutoSyncHttpClient.newCall(request).execute().use { response ->
        val bodyText = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            val snippet = bodyText.take(220)
            error("Gemini API request failed (${response.code}): $snippet")
        }
        val root = JSONObject(bodyText)
        val text = root.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text")
            .orEmpty()
        if (text.isBlank()) return@withContext null
        val parsed = runCatching { JSONObject(text) }.getOrNull() ?: return@withContext null
        SubtitleAutoSyncLlmResponse(json = parsed, rawText = text)
    }
}

internal fun PlayerRuntimeController.resolveInternalSubtitleTrackIndexForAutoSync(): Int? {
    val state = _uiState.value
    if (state.subtitleTracks.isEmpty()) return null

    // Auto Sync needs actual cue text; image-based (PGS/DVB/VobSub) tracks never produce any, so
    // fall back to a text-based track for calibration purposes even if that's not what's
    // currently selected/remembered for display.
    fun preferredTextIndex(candidateIndex: Int): Int {
        val candidate = state.subtitleTracks.getOrNull(candidateIndex) ?: return candidateIndex
        if (!candidate.isImageBased) return candidateIndex
        val candidateLanguage = candidate.language
        val textAlternative = candidateLanguage?.let { lang ->
            state.subtitleTracks.indices.firstOrNull { index ->
                !state.subtitleTracks[index].isImageBased &&
                    PlayerSubtitleUtils.matchesLanguageCode(state.subtitleTracks[index].language, lang)
            }
        } ?: state.subtitleTracks.indices.firstOrNull { !state.subtitleTracks[it].isImageBased }
        return textAlternative ?: candidateIndex
    }

    val selectedIndex = state.selectedSubtitleTrackIndex
    if (selectedIndex >= 0 && state.subtitleTracks.getOrNull(selectedIndex) != null) {
        return preferredTextIndex(selectedIndex)
    }
    // The user may have previously selected an internal track, then switched to an addon
    // (which resets selectedSubtitleTrackIndex to -1 but keeps this identity) — prefer it.
    lastStableInternalSubtitleTrackKey?.let { key ->
        val index = state.subtitleTracks.indexOfFirst { it.trackId == key }
        if (index >= 0) return preferredTextIndex(index)
    }
    // No internal track has ever been explicitly selected — auto-pick one so Auto Sync doesn't
    // require a manual selection first. Prefer English as the reference (most likely to be the
    // well-formed original, and what addon translations are usually derived from), then any
    // other normal (non-forced/signs-only) track, since forced tracks usually have too few lines
    // to reliably calibrate against. Image-based tracks are skipped entirely unless they're the
    // only option, since Auto Sync can't extract any text from them.
    val textCapableIndices = state.subtitleTracks.indices.filter { !state.subtitleTracks[it].isImageBased }
    val nonForcedIndices = (textCapableIndices.ifEmpty { state.subtitleTracks.indices.toList() })
        .filter { !state.subtitleTracks[it].isForced }
    val englishIndex = nonForcedIndices.firstOrNull {
        PlayerSubtitleUtils.matchesLanguageCode(state.subtitleTracks[it].language, "en")
    }
    if (englishIndex != null) return englishIndex
    return nonForcedIndices.firstOrNull() ?: textCapableIndices.firstOrNull() ?: 0
}

internal fun PlayerRuntimeController.reloadSubtitleAutoSyncCues() {
    maybeLoadSubtitleAutoSyncCues(force = true)
}

internal fun PlayerRuntimeController.resetSubtitleAutoSyncState(clearLoadedTrack: Boolean = true) {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncJob?.cancel()
    subtitleAutoSyncJob = null
    subtitleAutoSyncInFlightSessionKey = null
    // A track/addon change invalidates any in-flight or pending drift calibration, since it was
    // targeting the previous subtitle-track pairing's timeline (see Phase 4 in the drift file).
    cancelSubtitleDriftCalibration()
    // Otherwise a still-full buffer from the previous addon/track survives the switch, so the
    // retry loop's needsGather check thinks it already has enough lines and reuses stale cues
    // instead of gathering fresh ones for the newly selected addon subtitle.
    subtitleAutoSyncSourceCueBuffer.clear()
    subtitleAutoSyncSawNonTextCues = false
    // A cached parse from the previous addon file must not leak into the new selection.
    subtitleAutoSyncAddonCueCache = null
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLastLlmRequest = null,
            subtitleAutoSyncLastLlmResponse = null,
            subtitleDriftCorrectionInfo = null,
            subtitleDriftDebugTrace = null,
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

// Anchor1 (with its retry attempts) and anchor2 (with its own retry-with-nudge attempts) each
// used to independently re-download and re-parse the ENTIRE addon file on every single attempt,
// even though its content is identical across all of them within one calibration run — real
// network + parse latency stacked on top of every LLM round trip, making later attempts/retries
// visibly slower and less predictable. Cache by URL so only the first attempt pays that cost.
private suspend fun PlayerRuntimeController.loadAddonCuesCached(subtitle: Subtitle): List<SubtitleSyncCue> {
    subtitleAutoSyncAddonCueCache?.let { (cachedUrl, cachedCues) ->
        if (cachedUrl == subtitle.url) return cachedCues
    }
    val addonBody = downloadSubtitleBody(subtitle.url)
    val parsed = PlayerSubtitleCueParser.parseFromText(
        rawText = addonBody,
        sourceUrl = subtitle.url
    ).filter { it.text.isNotBlank() }
    subtitleAutoSyncAddonCueCache = subtitle.url to parsed
    return parsed
}

private suspend fun PlayerRuntimeController.downloadSubtitleBody(url: String): String =
    withContext(Dispatchers.IO) {
        val requestBuilder = Request.Builder().url(url)
        currentHeaders
            .filterKeys { key -> !key.equals("Range", ignoreCase = true) }
            .forEach { (key, value) ->
                requestBuilder.header(key, value)
            }
        requestBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
        val request = requestBuilder.build()

        try {
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
        } catch (e: SocketTimeoutException) {
            error("Subtitle file download timed out.")
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
