package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import com.nuvio.tv.core.network.IPv4FirstDns
import com.nuvio.tv.core.player.SubtitleCharsetDetector
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Interceptor
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

private fun OkHttpClient.Builder.subtitleDownloadDefaults(): OkHttpClient.Builder = this
    // Sidecar and auto-sync downloads use these clients; keep timeouts generous for flaky hosts.
    .connectTimeout(12_000, TimeUnit.MILLISECONDS)
    .readTimeout(15_000, TimeUnit.MILLISECONDS)
    .callTimeout(25_000, TimeUnit.MILLISECONDS)
    .retryOnConnectionFailure(true)
    .followRedirects(true)
    .followSslRedirects(true)
    .addNetworkInterceptor(ForwardedStreamHeaderGuard)

// Validating client. Interceptors are cleared so playbackHttpClient's trust-all SSL fallback can't
// resend stream credentials; executeSubtitleRequest handles the fallback instead.
internal val subtitleHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.playbackHttpClient.newBuilder()
        .apply { interceptors().clear() }
        .subtitleDownloadDefaults()
        .build()
}

internal val subtitleUnvalidatedTlsHttpClient: OkHttpClient by lazy {
    PlayerPlaybackNetworking.trustAllPlaybackHttpClient.newBuilder()
        .subtitleDownloadDefaults()
        .build()
}

private const val SUBTITLE_DOWNLOAD_MAX_ATTEMPTS = 3
private const val SUBTITLE_DOWNLOAD_RETRY_DELAY_MS = 350L

private const val AUTO_SYNC_REACTION_COMPENSATION_MS = 300L
private const val AUTO_SYNC_MAX_FAST_PROBES = 6
private const val AUTO_SYNC_VALIDATION_MAX_WALL_CLOCK_MS = 40_000L

private data class AutoSyncPlaybackSuspension(
    val exoPlayer: ExoPlayer? = null,
    val exoPositionMs: Long = 0L,
    val exoWasStopped: Boolean = false,
    val shouldResumePlayback: Boolean = false,
    val mpvWasPlaying: Boolean = false
)

internal fun PlayerRuntimeController.showSubtitleTimingDialog() {
    openSubtitleTimingDialog(runAutomaticSync = false)
}

internal fun PlayerRuntimeController.showSubtitleAutoSyncDialog() {
    openSubtitleTimingDialog(runAutomaticSync = true)
}

private fun PlayerRuntimeController.openSubtitleTimingDialog(runAutomaticSync: Boolean) {
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
            subtitleAutoSyncLoading = runAutomaticSync,
            subtitleAutoSyncStatus = if (runAutomaticSync) {
                context.getString(R.string.subtitle_auto_sync_checking)
            } else {
                null
            },
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
    if (runAutomaticSync) {
        startAutomaticSubtitleSync()
    } else {
        maybeLoadSubtitleAutoSyncCues(force = false)
    }
}

internal fun PlayerRuntimeController.dismissSubtitleTimingDialog() {
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncAttemptId++
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
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
    subtitleAutoSyncAttemptId++
    _uiState.update {
        it.copy(
            subtitleAutoSyncCues = emptyList(),
            subtitleAutoSyncCapturedVideoMs = null,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = null,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncLoadedTrackKey = if (clearLoadedTrack) null else it.subtitleAutoSyncLoadedTrackKey,
            subtitleAutoSyncAlternatives = emptyList()
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
                subtitleAutoSyncStatus = null,
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
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = null
            )
        }
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
            val rawSubtitleBody = downloadSubtitleBody(
                selectedSubtitle.url,
                selectedSubtitle.lang,
                selectedSubtitle.headers
            )
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
                    subtitleAutoSyncStatus = null,
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
                    subtitleAutoSyncStatus = null,
                    subtitleAutoSyncError = e.message ?: context.getString(com.nuvio.tv.R.string.subtitle_timing_load_lines_failed),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }
        }
    }
}

private fun PlayerRuntimeController.startAutomaticSubtitleSync() {
    val initialState = _uiState.value
    val selectedSubtitle = initialState.selectedAddonSubtitle
    if (selectedSubtitle == null) {
        _uiState.update {
            it.copy(
                subtitleAutoSyncLoading = false,
                subtitleAutoSyncStatus = null,
                subtitleAutoSyncError = context.getString(R.string.subtitle_auto_sync_select_addon_track),
                subtitleAutoSyncAlternatives = emptyList()
            )
        }
        return
    }

    subtitleAutoSyncLoadJob?.cancel()
    val attemptId = ++subtitleAutoSyncAttemptId
    val selectedTrackKey = selectedSubtitle.autoSyncTrackKey()
    val streamUrl = currentStreamUrl
    val streamHeaders = currentHeaders
    val selectedAudioTrack = initialState.audioTracks.firstOrNull {
        it.index == initialState.selectedAudioTrackIndex
    } ?: initialState.audioTracks.firstOrNull { it.isSelected }
    val playbackPositionAtStart = currentPlaybackPositionMs()
        ?: _playbackTimeline.value.currentPosition
    val mediaDurationAtStart = _playbackTimeline.value.duration
    // Auto Sync owns a completely independent, audio-only player. It is intentionally unrelated
    // to how much audio the main player has already played (including torrent and RTSP sources).
    val canUseSecondaryPlayer = streamUrl.isNotBlank()

    subtitleAutoSyncLoadJob = scope.launch {
        val playbackSuspension = suspendMainPlaybackForAutoSync()
        val fastProbe = SubtitleFastAudioProbe(context)
        var firstProbeDeferred: Deferred<SubtitleFastAudioProbeResult>? = null
        try {
            _uiState.update {
                it.copy(
                    subtitleAutoSyncLoading = true,
                    subtitleAutoSyncStatus = context.getString(R.string.subtitle_auto_sync_probing_audio),
                    subtitleAutoSyncError = null,
                    subtitleAutoSyncAlternatives = emptyList(),
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }

            val probePositions = planSubtitleAutoSyncProbePositions(
                currentPositionMs = playbackPositionAtStart,
                durationMs = mediaDurationAtStart,
                maxAttempts = AUTO_SYNC_MAX_FAST_PROBES
            )
            var probePlan = SubtitleFastAudioProbePolicy.plan(
                fileSizeBytes = currentVideoSize,
                durationMs = mediaDurationAtStart
            )
            Log.i(
                PlayerRuntimeController.TAG,
                "Subtitle Auto Sync probe policy: fileBytes=${currentVideoSize ?: -1L} " +
                    "duration=$mediaDurationAtStart " +
                    "bitrate=${probePlan.estimatedFileBitrateBps ?: -1} " +
                    "speed=${probePlan.playbackSpeed}x " +
                    "activeTimeout=${probePlan.activeDecodeTimeoutMs}ms"
            )
            firstProbeDeferred = if (canUseSecondaryPlayer && probePositions.isNotEmpty()) {
                val firstProbePlan = probePlan
                async {
                    fastProbe.probe(
                        SubtitleFastAudioProbeRequest(
                            streamUrl = streamUrl,
                            headers = streamHeaders,
                            preferredStartMs = probePositions.first(),
                            mediaDurationMs = mediaDurationAtStart,
                            selectedAudioTrack = selectedAudioTrack,
                            playbackSpeed = firstProbePlan.playbackSpeed,
                            maxWallClockMs = firstProbePlan.activeDecodeTimeoutMs
                        )
                    )
                }
            } else {
                null
            }

            val selectedCues = loadSubtitleAutoSyncCues(selectedSubtitle)
            if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) return@launch
            _uiState.update {
                it.copy(
                    subtitleAutoSyncCues = selectedCues,
                    subtitleAutoSyncLoadedTrackKey = selectedTrackKey
                )
            }

            // Audio probing is by far the expensive part of Auto Sync. Keep the alternative
            // subtitle bodies cached and score them against every accumulated probe snapshot,
            // instead of waiting for all six probes before trying a track that may match at once.
            var alternativeCandidates = SubtitleAutoSyncCandidateMatcher.alternatives(
                selected = selectedSubtitle,
                available = _uiState.value.addonSubtitles
            )
            val alternativeCueCache = mutableMapOf<String, List<SubtitleSyncCue>>()
            val failedAlternativeKeys = mutableSetOf<String>()
            var latestCandidateResults = emptyList<SubtitleAutoSyncCandidateResult>()
            var alternativesEvaluatedForLatestSnapshot = false
            val selectedProbeResults = mutableListOf<SubtitleAutoSyncResult>()
            val attemptedAlternativeValidations = mutableSetOf<String>()
            var targetedValidationAttempted = false

            val probeSnapshots = mutableListOf<SubtitleSpeechSnapshot>()
            val probeFailures = mutableListOf<String>()
            var snapshot = mergeAutoSyncSnapshots(
                probeSnapshots = probeSnapshots,
                probeFailure = null
            )
            var currentResult = analyzeAutoSyncCues(selectedCues, snapshot)

            if (canUseSecondaryPlayer) {
                for (probeIndex in probePositions.indices) {
                    ensureActive()
                    if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
                        return@launch
                    }
                    _uiState.update {
                        it.copy(
                            subtitleAutoSyncStatus = context.getString(
                                R.string.subtitle_auto_sync_probe_attempt,
                                probeIndex + 1,
                                probePositions.size
                            )
                        )
                    }

                    val usedProbePlan = probePlan
                    val probeResult = if (probeIndex == 0) {
                        firstProbeDeferred?.await()
                    } else {
                        fastProbe.probe(
                            SubtitleFastAudioProbeRequest(
                                streamUrl = streamUrl,
                                headers = streamHeaders,
                                preferredStartMs = probePositions[probeIndex],
                                mediaDurationMs = mediaDurationAtStart,
                                selectedAudioTrack = selectedAudioTrack,
                                playbackSpeed = usedProbePlan.playbackSpeed,
                                maxWallClockMs = usedProbePlan.activeDecodeTimeoutMs
                            )
                        )
                    } ?: continue

                    probePlan = SubtitleFastAudioProbePolicy.afterProbe(usedProbePlan, probeResult)
                    if (probePlan.playbackSpeed != usedProbePlan.playbackSpeed) {
                        Log.i(
                            PlayerRuntimeController.TAG,
                            "Subtitle Auto Sync probe backoff: ${usedProbePlan.playbackSpeed}x -> " +
                                "${probePlan.playbackSpeed}x nextActiveTimeout=" +
                                "${probePlan.activeDecodeTimeoutMs}ms"
                        )
                    }

                    probeResult.snapshot?.let(probeSnapshots::add)
                    probeResult.failureReason?.let(probeFailures::add)
                    snapshot = mergeAutoSyncSnapshots(
                        probeSnapshots = probeSnapshots,
                        probeFailure = probeFailures.lastOrNull()
                    )
                    alternativesEvaluatedForLatestSnapshot = false
                    val evidence = SubtitleAutoSyncEngine.measureAudioEvidence(snapshot)
                    Log.i(
                        PlayerRuntimeController.TAG,
                        "Subtitle Auto Sync probe ${probeIndex + 1}/${probePositions.size}: " +
                            "requested=${probePositions[probeIndex]} " +
                            "range=${probeResult.decodedStartMs}..${probeResult.decodedEndMs} " +
                            "decoded=${probeResult.decodedDurationMs}ms " +
                            "observed=${probeResult.observedDurationMs}ms " +
                            "termination=${probeResult.termination} evidence=${evidence.observedMs}ms/" +
                            "${evidence.windowCount} windows ready=${evidence.ready}"
                    )
                    currentResult = analyzeAutoSyncCues(selectedCues, snapshot)
                    logAutoSyncResult("selected-probe-${probeIndex + 1}", selectedSubtitle, currentResult)
                    // Consensus must use disjoint probe evidence. Adding cumulative results here
                    // would count the first probe repeatedly and let one false peak confirm itself.
                    val independentResult = probeResult.snapshot?.let { independentSnapshot ->
                        analyzeAutoSyncCues(selectedCues, independentSnapshot).also { result ->
                            logAutoSyncResult(
                                "selected-independent-${probeIndex + 1}",
                                selectedSubtitle,
                                result
                            )
                        }
                    }
                    independentResult?.let(selectedProbeResults::add)
                    val finalIndependentResult = independentResult
                        ?.takeIf { probeIndex == probePositions.lastIndex }
                    if (currentResult.shouldApply) {
                        finishAutoSyncForCurrentTrack(attemptId, currentResult)
                        return@launch
                    }
                    SubtitleAutoSyncProbeConsensus.stableResult(selectedProbeResults)?.let { consensus ->
                        logAutoSyncResult(
                            "selected-consensus-${selectedProbeResults.size}",
                            selectedSubtitle,
                            consensus
                        )
                        finishAutoSyncForCurrentTrack(attemptId, consensus)
                        return@launch
                    }

                    val targetedValidationCandidate = when {
                        targetedValidationAttempted -> null
                        SubtitleAutoSyncTargetedValidation.shouldStart(
                            candidate = currentResult,
                            isLastStandardProbe = probeIndex == probePositions.lastIndex
                        ) -> currentResult
                        finalIndependentResult != null &&
                            SubtitleAutoSyncTargetedValidation
                                .shouldStartFromFinalIndependentProbe(finalIndependentResult) ->
                            finalIndependentResult
                        else -> null
                    }
                    if (targetedValidationCandidate != null) {
                        targetedValidationAttempted = true
                        validateAutoSyncCandidate(
                            candidate = targetedValidationCandidate,
                            cues = selectedCues,
                            existingSnapshots = probeSnapshots,
                            fastProbe = fastProbe,
                            streamUrl = streamUrl,
                            streamHeaders = streamHeaders,
                            selectedAudioTrack = selectedAudioTrack,
                            probePlan = probePlan,
                            mediaDurationMs = mediaDurationAtStart,
                            attemptId = attemptId,
                            selectedTrackKey = selectedTrackKey,
                            selectedSubtitle = selectedSubtitle
                        )?.let { confirmed ->
                            finishAutoSyncForCurrentTrack(attemptId, confirmed)
                            return@launch
                        }
                    }

                    // Addon subtitle discovery can finish while audio probing is already running.
                    // Refresh the bounded list so a late same-language track is not missed.
                    alternativeCandidates = SubtitleAutoSyncCandidateMatcher.alternatives(
                        selected = selectedSubtitle,
                        available = _uiState.value.addonSubtitles
                    )
                    if (shouldTryAutoSyncAlternatives(currentResult) && alternativeCandidates.isNotEmpty()) {
                        latestCandidateResults = evaluateAutoSyncAlternatives(
                            candidates = alternativeCandidates,
                            snapshot = snapshot,
                            attemptId = attemptId,
                            selectedTrackKey = selectedTrackKey,
                            streamUrl = streamUrl,
                            cueCache = alternativeCueCache,
                            failedKeys = failedAlternativeKeys,
                            source = "alternative-probe-${probeIndex + 1}"
                        )
                        alternativesEvaluatedForLatestSnapshot = true
                        val winner = SubtitleAutoSyncCandidateMatcher.clearWinner(
                            results = latestCandidateResults,
                            currentResult = currentResult
                        )
                        if (winner != null) {
                            val winnerKey = winner.subtitle.autoSyncTrackKey()
                            if (attemptedAlternativeValidations.add(winnerKey)) {
                                val winnerCues = alternativeCues(
                                    candidate = winner.subtitle,
                                    key = winnerKey,
                                    cache = alternativeCueCache
                                )
                                validateAutoSyncCandidate(
                                    candidate = winner.result,
                                    cues = winnerCues,
                                    existingSnapshots = probeSnapshots,
                                    fastProbe = fastProbe,
                                    streamUrl = streamUrl,
                                    streamHeaders = streamHeaders,
                                    selectedAudioTrack = selectedAudioTrack,
                                    probePlan = probePlan,
                                    mediaDurationMs = mediaDurationAtStart,
                                    attemptId = attemptId,
                                    selectedTrackKey = selectedTrackKey,
                                    selectedSubtitle = winner.subtitle
                                )?.let { confirmed ->
                                    // Selecting another track resets Auto Sync state. Detach this
                                    // job so it cannot cancel itself during the track switch.
                                    subtitleAutoSyncLoadJob = null
                                    applyMatchedSubtitle(winner.subtitle, confirmed.offsetMs)
                                    return@launch
                                }
                            }
                        }
                    }
                }
            }

            // Score only PCM produced by the secondary player. Never wait for or merge audio from
            // the main playback path: "30 seconds" is an evidence target, not a real-time delay.
            snapshot = mergeAutoSyncSnapshots(
                probeSnapshots = probeSnapshots,
                probeFailure = probeFailures.lastOrNull()
            )
            currentResult = analyzeAutoSyncCues(selectedCues, snapshot)
            logAutoSyncResult("selected-final", selectedSubtitle, currentResult)
            if (currentResult.shouldApply) {
                finishAutoSyncForCurrentTrack(attemptId, currentResult)
                return@launch
            }

            if (
                shouldTryAutoSyncAlternatives(currentResult) &&
                SubtitleAutoSyncCandidateMatcher.alternatives(
                    selected = selectedSubtitle,
                    available = _uiState.value.addonSubtitles
                ).also { alternativeCandidates = it }.isNotEmpty() &&
                !alternativesEvaluatedForLatestSnapshot
            ) {
                latestCandidateResults = evaluateAutoSyncAlternatives(
                    candidates = alternativeCandidates,
                    snapshot = snapshot,
                    attemptId = attemptId,
                    selectedTrackKey = selectedTrackKey,
                    streamUrl = streamUrl,
                    cueCache = alternativeCueCache,
                    failedKeys = failedAlternativeKeys,
                    source = "alternative-final"
                )
            }

            if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) return@launch
            val ranked = SubtitleAutoSyncCandidateMatcher.rank(latestCandidateResults)
            val winner = SubtitleAutoSyncCandidateMatcher.clearWinner(
                results = ranked,
                currentResult = currentResult
            )
            if (winner != null) {
                val winnerKey = winner.subtitle.autoSyncTrackKey()
                if (attemptedAlternativeValidations.add(winnerKey)) {
                    val winnerCues = alternativeCues(
                        candidate = winner.subtitle,
                        key = winnerKey,
                        cache = alternativeCueCache
                    )
                    validateAutoSyncCandidate(
                        candidate = winner.result,
                        cues = winnerCues,
                        existingSnapshots = probeSnapshots,
                        fastProbe = fastProbe,
                        streamUrl = streamUrl,
                        streamHeaders = streamHeaders,
                        selectedAudioTrack = selectedAudioTrack,
                        probePlan = probePlan,
                        mediaDurationMs = mediaDurationAtStart,
                        attemptId = attemptId,
                        selectedTrackKey = selectedTrackKey,
                        selectedSubtitle = winner.subtitle
                    )?.let { confirmed ->
                        subtitleAutoSyncLoadJob = null
                        applyMatchedSubtitle(winner.subtitle, confirmed.offsetMs)
                        return@launch
                    }
                }
            }

            val alternatives = ranked
                .asSequence()
                .filter { it.result.shouldApply }
                .take(3)
                .map {
                    SubtitleAutoSyncAlternative(
                        trackKey = it.subtitle.autoSyncTrackKey(),
                        subtitle = it.subtitle,
                        offsetMs = it.result.offsetMs,
                        confidence = it.result.confidence
                    )
                }
                .toList()
            showAutoSyncFallback(currentResult, alternatives)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            Log.e(PlayerRuntimeController.TAG, "Subtitle Auto Sync failed", error)
            if (isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
                _uiState.update {
                    it.copy(
                        subtitleAutoSyncLoading = false,
                        subtitleAutoSyncStatus = null,
                        subtitleAutoSyncError = error.message
                            ?: context.getString(R.string.subtitle_auto_sync_failed),
                        subtitleAutoSyncAlternatives = emptyList()
                    )
                }
            }
        } finally {
            withContext(NonCancellable) {
                firstProbeDeferred?.cancelAndJoin()
            }
            fastProbe.release()
            restoreMainPlaybackAfterAutoSync(playbackSuspension, streamUrl)
        }
    }
}

private fun PlayerRuntimeController.suspendMainPlaybackForAutoSync(): AutoSyncPlaybackSuspension {
    if (isUsingMpvEngine()) {
        val wasPlaying = mpvView?.isPlayingNow() == true
        mpvView?.setPaused(true)
        Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync suspended main MPV playback")
        return AutoSyncPlaybackSuspension(
            shouldResumePlayback = wasPlaying,
            mpvWasPlaying = wasPlaying
        )
    }

    val player = _exoPlayer ?: return AutoSyncPlaybackSuspension()
    val shouldResume = player.playWhenReady && !userPausedManually
    val positionMs = player.currentPosition.coerceAtLeast(0L)
    val canStopLoading = player.currentMediaItem != null
    subtitleAutoSyncPlaybackSuspended = true
    player.playWhenReady = false
    player.pause()
    if (canStopLoading) {
        // pause() alone keeps filling ExoPlayer's buffer. stop() retains the media item and track
        // selection but cancels the loader, so the probe is the only large HTTP reader.
        player.stop()
    }
    Log.i(
        PlayerRuntimeController.TAG,
        "Subtitle Auto Sync suspended main Exo playback: position=$positionMs " +
            "stopped=$canStopLoading resume=$shouldResume"
    )
    return AutoSyncPlaybackSuspension(
        exoPlayer = player,
        exoPositionMs = positionMs,
        exoWasStopped = canStopLoading,
        shouldResumePlayback = shouldResume
    )
}

private fun PlayerRuntimeController.restoreMainPlaybackAfterAutoSync(
    suspension: AutoSyncPlaybackSuspension,
    expectedStreamUrl: String
) {
    if (currentStreamUrl != expectedStreamUrl) {
        subtitleAutoSyncPlaybackSuspended = false
        return
    }

    val suspendedExoPlayer = suspension.exoPlayer
    if (suspendedExoPlayer != null) {
        if (_exoPlayer !== suspendedExoPlayer) {
            subtitleAutoSyncPlaybackSuspended = false
            return
        }
        val resume = suspension.shouldResumePlayback && !userPausedManually
        if (!resume) {
            // PlayerStartupPlaybackPolicy resumes every post-first-frame READY unless the pause is
            // explicit. Preserve the pre-Auto-Sync paused state across stop()/prepare().
            userPausedManually = true
        }
        if (suspension.exoWasStopped) {
            suspendedExoPlayer.seekTo(suspension.exoPositionMs)
            suspendedExoPlayer.prepare()
        }
        suspendedExoPlayer.playWhenReady = resume
        if (resume) suspendedExoPlayer.play() else suspendedExoPlayer.pause()
        subtitleAutoSyncPlaybackSuspended = false
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync restored main Exo playback: position=${suspension.exoPositionMs} " +
                "resume=$resume"
        )
        return
    }

    subtitleAutoSyncPlaybackSuspended = false
    if (suspension.mpvWasPlaying && !userPausedManually) {
        mpvView?.setPaused(false)
        Log.i(PlayerRuntimeController.TAG, "Subtitle Auto Sync restored main MPV playback")
    }
}

private fun shouldTryAutoSyncAlternatives(result: SubtitleAutoSyncResult): Boolean =
    result.rejection == SubtitleAutoSyncRejection.LOW_CONFIDENCE ||
        result.rejection == SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE

private suspend fun PlayerRuntimeController.validateAutoSyncCandidate(
    candidate: SubtitleAutoSyncResult,
    cues: List<SubtitleSyncCue>,
    existingSnapshots: List<SubtitleSpeechSnapshot>,
    fastProbe: SubtitleFastAudioProbe,
    streamUrl: String,
    streamHeaders: Map<String, String>,
    selectedAudioTrack: TrackInfo?,
    probePlan: SubtitleFastAudioProbePlan,
    mediaDurationMs: Long,
    attemptId: Long,
    selectedTrackKey: String,
    selectedSubtitle: Subtitle
): SubtitleAutoSyncResult? {
    var validationProbePlan = probePlan
    val positions = planSubtitleAutoSyncValidationPositions(
        cues = cues,
        candidateOffsetMs = candidate.offsetMs,
        durationMs = mediaDurationMs,
        excludedObservedSpans = existingSnapshots.flatMap { it.observedSpans }
    )
    if (positions.size < SubtitleAutoSyncTargetedValidation.MAX_PROBES) {
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync targeted validation skipped: candidate=${candidate.offsetMs} " +
                "independentWindows=${positions.size}"
        )
        return null
    }

    val validationResults = mutableListOf<SubtitleAutoSyncResult>()
    for ((index, positionMs) in positions.withIndex()) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
            throw CancellationException("Auto Sync selection changed")
        }
        _uiState.update {
            it.copy(
                subtitleAutoSyncStatus = context.getString(
                    R.string.subtitle_auto_sync_confirming_timing,
                    index + 1,
                    positions.size
                )
            )
        }

        val probeResult = fastProbe.probe(
            SubtitleFastAudioProbeRequest(
                streamUrl = streamUrl,
                headers = streamHeaders,
                preferredStartMs = positionMs,
                mediaDurationMs = mediaDurationMs,
                selectedAudioTrack = selectedAudioTrack,
                playbackSpeed = validationProbePlan.playbackSpeed,
                maxWallClockMs = maxOf(
                    AUTO_SYNC_VALIDATION_MAX_WALL_CLOCK_MS,
                    validationProbePlan.activeDecodeTimeoutMs
                )
            )
        )
        validationProbePlan = SubtitleFastAudioProbePolicy.afterProbe(
            validationProbePlan,
            probeResult
        )
        Log.i(
            PlayerRuntimeController.TAG,
            "Subtitle Auto Sync validation ${index + 1}/${positions.size}: " +
                "candidate=${candidate.offsetMs} requested=$positionMs " +
                "range=${probeResult.decodedStartMs}..${probeResult.decodedEndMs} " +
                "decoded=${probeResult.decodedDurationMs}ms " +
                "observed=${probeResult.observedDurationMs}ms " +
                "termination=${probeResult.termination}"
        )
        val validationSnapshot = probeResult.snapshot ?: return null
        val validationResult = analyzeAutoSyncCues(
            cues = cues,
            snapshot = validationSnapshot,
            minimumOffsetMs = candidate.offsetMs - SubtitleAutoSyncTargetedValidation.SEARCH_RADIUS_MS,
            maximumOffsetMs = candidate.offsetMs + SubtitleAutoSyncTargetedValidation.SEARCH_RADIUS_MS
        )
        logAutoSyncResult(
            source = "selected-validation-${index + 1}",
            subtitle = selectedSubtitle,
            result = validationResult
        )
        validationResults += validationResult
        if (!SubtitleAutoSyncTargetedValidation.confirms(candidate.offsetMs, validationResult)) {
            return null
        }
    }

    return SubtitleAutoSyncTargetedValidation.confirmedResult(candidate, validationResults)
        ?.also { confirmed ->
            logAutoSyncResult("selected-validation-confirmed", selectedSubtitle, confirmed)
        }
}

private suspend fun PlayerRuntimeController.evaluateAutoSyncAlternatives(
    candidates: List<Subtitle>,
    snapshot: SubtitleSpeechSnapshot,
    attemptId: Long,
    selectedTrackKey: String,
    streamUrl: String,
    cueCache: MutableMap<String, List<SubtitleSyncCue>>,
    failedKeys: MutableSet<String>,
    source: String
): List<SubtitleAutoSyncCandidateResult> {
    if (candidates.isEmpty()) return emptyList()

    val languageName = _uiState.value.selectedAddonSubtitle
        ?.let { Subtitle.languageCodeToName(it.lang) }
        .orEmpty()
    _uiState.update {
        it.copy(
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_checking_alternatives,
                languageName
            )
        )
    }

    val results = mutableListOf<SubtitleAutoSyncCandidateResult>()
    for (candidate in candidates) {
        currentCoroutineContext().ensureActive()
        if (!isCurrentAutoSyncAttempt(attemptId, selectedTrackKey, streamUrl)) {
            throw CancellationException("Auto Sync selection changed")
        }

        val candidateKey = candidate.autoSyncTrackKey()
        if (candidateKey in failedKeys) continue
        try {
            val candidateCues = alternativeCues(
                candidate = candidate,
                key = candidateKey,
                cache = cueCache
            )
            val result = analyzeAutoSyncCues(candidateCues, snapshot)
            logAutoSyncResult(source, candidate, result)
            results += SubtitleAutoSyncCandidateResult(candidate, result)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            failedKeys += candidateKey
            Log.w(
                PlayerRuntimeController.TAG,
                "Subtitle Auto Sync skipped alternative ${candidate.id}: ${error.message}"
            )
        }
    }
    return results
}

private suspend fun PlayerRuntimeController.alternativeCues(
    candidate: Subtitle,
    key: String,
    cache: MutableMap<String, List<SubtitleSyncCue>>
): List<SubtitleSyncCue> {
    cache[key]?.let { return it }
    return loadSubtitleAutoSyncCues(candidate).also { cache[key] = it }
}

internal fun PlayerRuntimeController.applySubtitleAutoSyncAlternative(trackKey: String) {
    val alternative = _uiState.value.subtitleAutoSyncAlternatives
        .firstOrNull { it.trackKey == trackKey }
        ?: return
    subtitleAutoSyncLoadJob?.cancel()
    subtitleAutoSyncLoadJob = null
    subtitleAutoSyncAttemptId++
    applyMatchedSubtitle(alternative.subtitle, alternative.offsetMs)
}

private fun PlayerRuntimeController.applyMatchedSubtitle(subtitle: Subtitle, offsetMs: Int) {
    autoSubtitleSelected = true
    rememberAddonSubtitleSelection(subtitle)
    selectAddonSubtitle(subtitle)
    setSubtitleDelayMs(targetMs = offsetMs, showOverlay = true)
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(offsetMs)
            ),
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
}

private fun PlayerRuntimeController.finishAutoSyncForCurrentTrack(
    attemptId: Long,
    result: SubtitleAutoSyncResult
) {
    if (subtitleAutoSyncAttemptId != attemptId) return
    setSubtitleDelayMs(targetMs = result.offsetMs, showOverlay = true)
    _uiState.update {
        it.copy(
            showSubtitleTimingDialog = false,
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = context.getString(
                R.string.subtitle_auto_sync_applied,
                formatAutoSyncDelay(result.offsetMs)
            ),
            subtitleAutoSyncError = null,
            subtitleAutoSyncAlternatives = emptyList()
        )
    }
}

private fun PlayerRuntimeController.isCurrentAutoSyncAttempt(
    attemptId: Long,
    selectedTrackKey: String,
    streamUrl: String
): Boolean = subtitleAutoSyncAttemptId == attemptId &&
    currentStreamUrl == streamUrl &&
    _uiState.value.selectedAddonSubtitle?.autoSyncTrackKey() == selectedTrackKey

private suspend fun PlayerRuntimeController.loadSubtitleAutoSyncCues(
    subtitle: Subtitle
): List<SubtitleSyncCue> {
    val rawSubtitleBody = downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
    return PlayerSubtitleCueParser.parseFromText(
        rawText = rawSubtitleBody,
        sourceUrl = subtitle.url
    )
        .filter { it.text.isNotBlank() }
        .ifEmpty { error(context.getString(R.string.subtitle_timing_file_no_lines)) }
}

private suspend fun analyzeAutoSyncCues(
    cues: List<SubtitleSyncCue>,
    snapshot: SubtitleSpeechSnapshot,
    minimumOffsetMs: Int? = null,
    maximumOffsetMs: Int? = null
): SubtitleAutoSyncResult = withContext(Dispatchers.Default) {
    SubtitleAutoSyncEngine.findBestOffset(
        cues = cues,
        snapshot = snapshot,
        minimumOffsetMs = minimumOffsetMs,
        maximumOffsetMs = maximumOffsetMs
    )
}

private fun mergeAutoSyncSnapshots(
    probeSnapshots: List<SubtitleSpeechSnapshot>,
    probeFailure: String?
): SubtitleSpeechSnapshot {
    val snapshots = probeSnapshots
    return SubtitleSpeechSnapshot(
        speechSpans = SubtitleAutoSyncEngine.mergeSpans(snapshots.flatMap { it.speechSpans }, 300L),
        observedSpans = SubtitleAutoSyncEngine.mergeSpans(snapshots.flatMap { it.observedSpans }, 120L),
        pcmAvailable = snapshots.any { it.pcmAvailable },
        failureReason = snapshots.firstNotNullOfOrNull { it.failureReason }
            ?: probeFailure
    )
}

/**
 * Plans non-overlapping probe windows. Known-duration media is sampled across its timeline; when
 * duration is unknown, retries move backwards so a playhead close to EOF does not keep returning
 * the same short tail.
 */
internal fun planSubtitleAutoSyncProbePositions(
    currentPositionMs: Long,
    durationMs: Long,
    maxAttempts: Int = AUTO_SYNC_MAX_FAST_PROBES
): List<Long> {
    if (maxAttempts <= 0) return emptyList()
    val current = currentPositionMs.coerceAtLeast(0L).let { position ->
        if (durationMs > 0L) position.coerceAtMost(durationMs) else position
    }
    val candidates = if (durationMs > 0L) {
        listOf(
            current,
            durationMs / 4L,
            durationMs / 2L,
            (durationMs * 3L) / 4L,
            (durationMs - 90_000L).coerceAtLeast(0L),
            0L
        )
    } else if (current >= 120_000L) {
        listOf(
            current,
            (current - 90_000L).coerceAtLeast(0L),
            (current - 180_000L).coerceAtLeast(0L),
            current + 90_000L,
            0L
        )
    } else {
        listOf(current, current + 90_000L, current + 180_000L, 0L)
    }

    fun effectiveStart(preferredMs: Long): Long {
        val preRolled = (preferredMs - 5_000L).coerceAtLeast(0L)
        return if (durationMs > 0L) {
            preRolled.coerceAtMost((durationMs - 60_000L).coerceAtLeast(0L))
        } else {
            preRolled
        }
    }

    val selected = mutableListOf<Long>()
    val effectiveStarts = mutableListOf<Long>()
    for (candidate in candidates) {
        val preferred = candidate.coerceAtLeast(0L)
        val effective = effectiveStart(preferred)
        // Probe windows decode up to 60 seconds. Keep their starts farther apart so two samples
        // cannot mostly observe the same dialogue and masquerade as independent evidence.
        if (effectiveStarts.none { kotlin.math.abs(it - effective) < 75_000L }) {
            selected += preferred
            effectiveStarts += effective
        }
        if (selected.size >= maxAttempts) break
    }
    return selected.ifEmpty { listOf(0L) }
}

private fun PlayerRuntimeController.logAutoSyncResult(
    source: String,
    subtitle: Subtitle,
    result: SubtitleAutoSyncResult
) {
    Log.i(
        PlayerRuntimeController.TAG,
        "Subtitle Auto Sync $source id=${subtitle.id}: offset=${result.offsetMs} " +
            "confidence=${result.confidence} margin=${result.scoreMargin} sigma=${result.sigma} " +
            "agreement=${result.windowAgreement} windows=${result.evidenceWindows} " +
            "rejection=${result.rejection}"
    )
}

private fun PlayerRuntimeController.showAutoSyncFallback(
    result: SubtitleAutoSyncResult,
    alternatives: List<SubtitleAutoSyncAlternative>
) {
    val fallbackMessage = if (alternatives.isNotEmpty()) {
        context.getString(R.string.subtitle_auto_sync_alternatives_found)
    } else {
        autoSyncFallbackMessage(result)
    }
    _uiState.update {
        it.copy(
            subtitleAutoSyncLoading = false,
            subtitleAutoSyncStatus = null,
            subtitleAutoSyncError = fallbackMessage,
            subtitleAutoSyncAlternatives = alternatives
        )
    }
}

private fun PlayerRuntimeController.autoSyncFallbackMessage(result: SubtitleAutoSyncResult): String =
    when (result.rejection) {
        SubtitleAutoSyncRejection.PCM_UNAVAILABLE ->
            context.getString(R.string.subtitle_auto_sync_pcm_unavailable)
        SubtitleAutoSyncRejection.NOT_ENOUGH_AUDIO ->
            context.getString(R.string.subtitle_auto_sync_need_more_audio)
        SubtitleAutoSyncRejection.NOT_ENOUGH_DIALOGUE ->
            context.getString(R.string.subtitle_auto_sync_not_enough_dialogue)
        SubtitleAutoSyncRejection.LOW_CONFIDENCE,
        SubtitleAutoSyncRejection.NONE ->
            context.getString(
                R.string.subtitle_auto_sync_low_confidence,
                (result.confidence * 100.0).roundToInt()
            )
    }

/**
 * Downloads a remote subtitle body for sidecar rendering / auto-sync.
 *
 * Stream headers are scoped to the stream's host; see [subtitleStreamHeaders].
 */
internal suspend fun PlayerRuntimeController.downloadSubtitleBody(
    url: String,
    languageHint: String? = null,
    headers: Map<String, String>? = null
): String =
    withContext(Dispatchers.IO) {
        var lastError: Exception? = null
        repeat(SUBTITLE_DOWNLOAD_MAX_ATTEMPTS) { attempt ->
            try {
                return@withContext executeSubtitleDownload(url, languageHint, headers)
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

// Request control headers, never copied to a subtitle request from the stream or the subtitle.
private val SUBTITLE_REQUEST_EXCLUDED_HEADERS = setOf("range", "host", "connection", "transfer-encoding")

// Stream headers allowed on other hosts (#3328).
private val SUBTITLE_CROSS_HOST_HEADERS = setOf("referer", "origin", "user-agent", "accept-language")

// Not forwarded on an HTTPS to HTTP downgrade.
private val SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS = setOf("referer", "origin")

private fun isDowngrade(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean =
    scopeUrl != null && scopeUrl.isHttps && !requestUrl.isHttps

/** Same host as [scopeUrl] and no HTTPS to HTTP downgrade. Sibling subdomains are out of scope. */
internal fun isInHeaderScope(requestUrl: HttpUrl, scopeUrl: HttpUrl?): Boolean {
    if (scopeUrl == null) return false
    if (isDowngrade(requestUrl, scopeUrl)) return false
    return requestUrl.host == scopeUrl.host
}

/**
 * All stream headers in scope, only [SUBTITLE_CROSS_HOST_HEADERS] outside it. Credentials can use any
 * header name, so this is an allowlist.
 */
internal fun subtitleStreamHeaders(
    streamHeaders: Map<String, String>,
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?
): Map<String, String> {
    val inScope = isInHeaderScope(subtitleUrl, streamUrl)
    val downgrade = isDowngrade(subtitleUrl, streamUrl)
    return streamHeaders.filterKeys { name ->
        val lower = name.lowercase()
        when {
            lower in SUBTITLE_REQUEST_EXCLUDED_HEADERS -> false
            inScope -> true
            downgrade && lower in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS -> false
            else -> lower in SUBTITLE_CROSS_HOST_HEADERS
        }
    }
}

/**
 * Stream headers scoped to the stream URL: [names] are removed on a hop outside it, [downgradeNames] on an
 * HTTPS to HTTP hop. Subtitle-owned headers are tracked by [SubtitleOwnHeaders].
 */
internal class ForwardedStreamHeaders(
    val streamUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String> = emptySet()
)

/**
 * The subtitle's own headers, scoped to the subtitle URL's host the same way: [names] are removed on a hop
 * to another host, [downgradeNames] on an HTTPS to HTTP hop.
 */
internal class SubtitleOwnHeaders(
    val subtitleUrl: HttpUrl,
    val names: Set<String>,
    val downgradeNames: Set<String>
)

/**
 * Applies [ForwardedStreamHeaders] and [SubtitleOwnHeaders] per redirect hop; OkHttp itself only strips
 * Authorization. Relies on OkHttp carrying request tags into redirects. It never adds headers back, so an
 * HTTP URL redirecting to HTTPS doesn't regain them.
 */
internal object ForwardedStreamHeaderGuard : Interceptor {
    override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
        val request = chain.request()
        val url = request.url
        val removed = mutableSetOf<String>()
        request.tag(ForwardedStreamHeaders::class.java)?.let { stream ->
            if (!isInHeaderScope(url, stream.streamUrl)) removed += stream.names
            if (isDowngrade(url, stream.streamUrl)) removed += stream.downgradeNames
        }
        request.tag(SubtitleOwnHeaders::class.java)?.let { own ->
            if (!isInHeaderScope(url, own.subtitleUrl)) removed += own.names
            if (isDowngrade(url, own.subtitleUrl)) removed += own.downgradeNames
        }
        return chain.proceed(request.withoutHeaders(removed))
    }
}

/** Removes the host-scoped stream and subtitle headers, for the permissive TLS retry. */
internal fun Request.withoutCredentialHeaders(): Request =
    withoutHeaders(
        tag(ForwardedStreamHeaders::class.java)?.names.orEmpty() +
            tag(SubtitleOwnHeaders::class.java)?.names.orEmpty()
    )

private fun Request.withoutHeaders(names: Set<String>): Request =
    if (names.isEmpty()) this else newBuilder().apply { names.forEach { removeHeader(it) } }.build()

/** Scoped stream headers, then the subtitle's own headers, then defaults, tagged for the guard. */
internal fun buildSubtitleRequest(
    subtitleUrl: HttpUrl,
    streamUrl: HttpUrl?,
    streamHeaders: Map<String, String>,
    explicitHeaders: Map<String, String>?
): Request {
    val requestBuilder = Request.Builder().url(subtitleUrl)

    val scopedStreamHeaders = subtitleStreamHeaders(streamHeaders, subtitleUrl, streamUrl)
    scopedStreamHeaders.forEach { (key, value) -> requestBuilder.header(key, value) }

    // Explicit subtitle headers override stream headers.
    explicitHeaders?.forEach { (key, value) ->
        if (key.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS) {
            requestBuilder.header(key, value)
        }
    }

    // A stream User-Agent is always allowed through, so this only fills the gap.
    if (requestBuilder.build().header("User-Agent") == null) {
        requestBuilder.header(
            "User-Agent",
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        )
    }

    if (requestBuilder.build().header("Accept") == null) {
        requestBuilder.header("Accept", "text/plain, text/vtt, application/x-subrip, */*")
    }

    val explicitNames = explicitHeaders?.keys.orEmpty().map { it.lowercase() }.toSet()
    val forwardedNames = scopedStreamHeaders.keys
        .filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    val downgradeNames = scopedStreamHeaders.keys
        .filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS && it.lowercase() !in explicitNames }
        .toSet()
    if (streamUrl != null && (forwardedNames.isNotEmpty() || downgradeNames.isNotEmpty())) {
        requestBuilder.tag(
            ForwardedStreamHeaders::class.java,
            ForwardedStreamHeaders(streamUrl, forwardedNames, downgradeNames)
        )
    }
    val sentOwnNames = explicitHeaders?.keys.orEmpty()
        .filter { it.lowercase() !in SUBTITLE_REQUEST_EXCLUDED_HEADERS }
    val ownNames = sentOwnNames.filter { it.lowercase() !in SUBTITLE_CROSS_HOST_HEADERS }.toSet()
    val ownDowngradeNames = sentOwnNames.filter { it.lowercase() in SUBTITLE_DOWNGRADE_EXCLUDED_HEADERS }.toSet()
    if (ownNames.isNotEmpty() || ownDowngradeNames.isNotEmpty()) {
        requestBuilder.tag(
            SubtitleOwnHeaders::class.java,
            SubtitleOwnHeaders(subtitleUrl, ownNames, ownDowngradeNames)
        )
    }
    return requestBuilder.build()
}

/**
 * Tries [validated] first. On any SSLException, reruns the whole redirect chain on [permissive] without
 * host-scoped stream or subtitle headers, so self-signed hosts still work.
 */
internal fun executeSubtitleRequest(
    request: Request,
    validated: OkHttpClient = subtitleHttpClient,
    permissive: OkHttpClient = subtitleUnvalidatedTlsHttpClient
): okhttp3.Response =
    try {
        validated.newCall(request).execute()
    } catch (e: SSLException) {
        permissive.newCall(request.withoutCredentialHeaders()).execute()
    }

private fun PlayerRuntimeController.executeSubtitleDownload(
    url: String,
    languageHint: String? = null,
    customHeaders: Map<String, String>? = null
): String {
    val explicitHeaders = customHeaders
        ?: streamSubtitles.firstOrNull { it.url == url }?.headers
        ?: _uiState.value.addonSubtitles.firstOrNull { it.url == url }?.headers
        ?: _uiState.value.selectedAddonSubtitle?.takeIf { it.url == url }?.headers
    val request = buildSubtitleRequest(
        subtitleUrl = url.toHttpUrl(),
        streamUrl = currentStreamUrl.toHttpUrlOrNull(),
        streamHeaders = currentHeaders,
        explicitHeaders = explicitHeaders
    )

    val response = executeSubtitleRequest(request)
    response.use {
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
