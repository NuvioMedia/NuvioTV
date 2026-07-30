package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Handler
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.audio.AudioRendererEventListener
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.roundToLong

// Bounded gather timeout for the invisible secondary extraction, per target attempt. Generous on
// purpose: this runs fully in the background with no user-facing cost to waiting longer, and even
// a "normal" mid-episode window isn't guaranteed 5 dialogue lines quickly (scene transitions,
// sight gags, brief cutaways). Only give up on a target once it's genuinely gone this long with
// nothing usable - the retry-with-nudge loop (see DRIFT_MAX_TARGET_RETRY_ATTEMPTS) handles a
// target landing in a truly extended quiet/music stretch.
private const val DRIFT_GATHER_TIMEOUT_MS = 300_000L

// Never target within this margin of either edge of the content.
private const val DRIFT_TRAILING_SAFETY_MARGIN_MS = 150_000L

// Below this separation between anchors, a drift rate can't be measured reliably above matching
// noise — skip rather than risk applying a wild rate from a too-close pair of points.
private const val DRIFT_MIN_SEPARATION_MS = 90_000L

// If a target position turns out to be an extended music/no-dialogue stretch (gather times out
// with zero valid lines), retry at a nudged nearby position rather than giving up on the whole
// calibration - bounded so a persistently bad stream still fails safely.
private const val DRIFT_MAX_TARGET_RETRY_ATTEMPTS = 3
private const val DRIFT_TARGET_NUDGE_MS = 180_000L

// Total calibration points including anchor1 (so up to 3 additional background-gathered
// anchors). More points let a least-squares fit average out per-anchor noise instead of an exact
// 2-point solve, and let the residual check below detect a single global rate not fitting well -
// capped here to bound background LLM-call cost/latency.
private const val DRIFT_MAX_TOTAL_ANCHORS = 4

// Fractions of the safety-margin-trimmed usable room (from anchor1 toward whichever edge has
// more room) at which each additional anchor is targeted, spreading them apart rather than
// clustering - a wider spread gives a much stronger/lower-variance slope estimate. One entry per
// anchor beyond anchor1 (so DRIFT_MAX_TOTAL_ANCHORS - 1 entries). All of these are gathered via
// the invisible secondary player's instant seeks, back-to-back, right after anchor1 - no need to
// wait for real playback time to reach them, since the drift model is position-based, not
// wall-clock-based.
private val DRIFT_ANCHOR_ROOM_FRACTIONS = doubleArrayOf(0.5, 0.667, 0.833)

// Per-anchor pooling (mirrors anchor1's own multi-attempt pooling, see AUTO_SYNC_MAX_ATTEMPTS/
// AUTO_SYNC_TARGET_POOLED_OFFSETS): a single LLM call can return as few as 1 confident pair,
// which made anchor2 historically the noisiest input to the rate. Kept lower than anchor1's own
// pooling budget since with DRIFT_MAX_TOTAL_ANCHORS anchors, the regression itself now provides
// redundancy across anchors too - no need for each individual anchor to be as bulletproof alone.
private const val DRIFT_ANCHOR_POOL_MAX_ATTEMPTS = 2
private const val DRIFT_ANCHOR_POOL_TARGET_OFFSETS = 2

// Realistic frame-rate mismatches (23.976/24/25fps combinations) are all well under this; a
// larger computed rate almost certainly means a bad LLM match, not a real drift — reject it.
private const val DRIFT_RATE_MAX_ABS = 0.08

// With >=3 anchors, how far an anchor's actual delay is allowed to sit from what the fitted line
// predicts before it's treated as a bad single measurement (most likely a bad OCR/LLM match, not
// genuine non-constant drift - see repo docs) rather than normal noise.
private const val DRIFT_RESIDUAL_OUTLIER_MS = 400L

// Best-effort bandwidth-contention guard: give the primary player's buffer a moment to be
// healthy before opening a second concurrent connection. Never blocks indefinitely on this.
private const val DRIFT_BUFFER_HEALTH_CHECK_ATTEMPTS = 5
private const val DRIFT_BUFFER_HEALTH_CHECK_INTERVAL_MS = 1_000L
private const val DRIFT_BUFFER_HEALTH_MIN_MARGIN_MS = 5_000L

// Logged with this prefix so it's easy to grep in logcat, since every failure path here is
// deliberately silent to the user (fail-safe) and would otherwise be undebuggable.
private const val DRIFT_LOG_TAG = "SubtitleDriftCalib"

// Also mirrored into UI state (see driftLog below) so it's visible on-device (TV, no adb access)
// via the existing "Show LLM Payload" dialog, not just logcat.
private const val DRIFT_DEBUG_TRACE_MAX_CHARS = 4000

private fun PlayerRuntimeController.driftLog(message: String) {
    Log.d(DRIFT_LOG_TAG, message)
    _uiState.update { state ->
        val existing = state.subtitleDriftDebugTrace
        val combined = if (existing.isNullOrBlank()) message else "$existing\n$message"
        state.copy(subtitleDriftDebugTrace = combined.takeLast(DRIFT_DEBUG_TRACE_MAX_CHARS))
    }
}

/**
 * Kicks off a best-effort second calibration point to detect/correct a frame-rate-style linear
 * drift on top of anchor1's single-point delay. Entirely background: seeks an invisible,
 * text-only secondary player straight to a distant point (no video/audio, never touches what's
 * displayed) rather than waiting for real playback time to pass. Any failure silently leaves the
 * original single-point delay from anchor1 untouched (fail-safe).
 */
internal fun PlayerRuntimeController.maybeStartSubtitleDriftCalibration(
    subtitle: Subtitle,
    internalTrack: TrackInfo,
    anchor1PositionMs: Long,
    anchor1DelayMs: Long
) {
    if (isUsingMpvEngine()) {
        driftLog("skip: mpv engine in use")
        return
    }
    if (internalTrack.isImageBased) {
        // Anchor2 would rely entirely on repeated ML Kit OCR of subtitle bitmaps - much noisier
        // than real text (misreads on compressed images) and not worth the extra CPU cost on a
        // TV device just to refine what's already a fragile single-point OCR-derived anchor1.
        driftLog("skip: internal track is image-based (OCR-only) - not reliable enough for a second anchor")
        return
    }
    val durationMs = currentPlaybackDurationMs()
    if (durationMs <= 0L) {
        driftLog("skip: unknown/zero duration ($durationMs)")
        return
    }

    // Bidirectional target selection: pick whichever side of anchor1 (toward file start or file
    // end) has more available room, so this works regardless of where auto-sync happened to run
    // (mid-episode, near the very end, etc.) without needing to skip due to short forward runway.
    val forwardRoomMs = durationMs - anchor1PositionMs
    val backwardRoomMs = anchor1PositionMs
    val useForward = forwardRoomMs >= backwardRoomMs
    val availableRoomMs = if (useForward) forwardRoomMs else backwardRoomMs
    if (availableRoomMs < DRIFT_MIN_SEPARATION_MS + DRIFT_TRAILING_SAFETY_MARGIN_MS) {
        // Both directions are too short for a meaningful, safely-clear-of-the-edges target.
        driftLog(
            "skip: not enough room either direction (available=${availableRoomMs}ms, " +
                "durationMs=$durationMs, anchor1PositionMs=$anchor1PositionMs)"
        )
        return
    }
    val usableRoomMs = (availableRoomMs - DRIFT_TRAILING_SAFETY_MARGIN_MS).coerceAtLeast(0L)
    // Spread anchors 2..N across the available room per DRIFT_ANCHOR_ROOM_FRACTIONS (anchor2 at
    // the true midpoint, same as before; later anchors progressively further out) rather than a
    // single fixed target - a bigger spread gives a stronger, more noise-resistant fit.
    val plannedTargetPositionsMs = DRIFT_ANCHOR_ROOM_FRACTIONS.map { fraction ->
        (if (useForward) {
            anchor1PositionMs + (usableRoomMs * fraction).toLong()
        } else {
            anchor1PositionMs - (usableRoomMs * fraction).toLong()
        }).coerceIn(0L, durationMs)
    }
    driftLog(
        "scheduling: anchor1PositionMs=$anchor1PositionMs anchor1DelayMs=$anchor1DelayMs " +
            "plannedTargetPositionsMs=$plannedTargetPositionsMs useForward=$useForward durationMs=$durationMs " +
            "internalTrack=index:${internalTrack.index}/id:${internalTrack.trackId}/lang:${internalTrack.language}"
    )

    val token = ++subtitleDriftCalibrationSessionToken
    subtitleDriftCalibrationJob?.cancel()
    subtitleDriftCalibrationJob = scope.launch {
        try {
            runSubtitleDriftCalibration(
                subtitle = subtitle,
                internalTrack = internalTrack,
                anchor1PositionMs = anchor1PositionMs,
                anchor1DelayMs = anchor1DelayMs,
                plannedTargetPositionsMs = plannedTargetPositionsMs,
                useForward = useForward,
                sessionToken = token
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Fail-safe: any failure here just means no drift correction lands this session.
            driftLog("failed, keeping single-point delay: $e")
        }
    }
}

/** Invalidates any in-flight/pending drift calibration — call on track or media-item changes. */
internal fun PlayerRuntimeController.cancelSubtitleDriftCalibration() {
    subtitleDriftCalibrationSessionToken++
    subtitleDriftCalibrationJob?.cancel()
    subtitleDriftCalibrationJob = null
}

private data class DriftAnchorPoint(val positionMs: Long, val delayMs: Long)

/** Unweighted least-squares line: delayMs = interceptMs + rate * positionMs. */
private data class DriftLinearFit(val rate: Double, val interceptMs: Double) {
    fun predictMs(positionMs: Long): Double = interceptMs + rate * positionMs
}

private fun fitDriftLine(anchors: List<DriftAnchorPoint>): DriftLinearFit {
    val meanX = anchors.sumOf { it.positionMs.toDouble() } / anchors.size
    val meanY = anchors.sumOf { it.delayMs.toDouble() } / anchors.size
    var numerator = 0.0
    var denominator = 0.0
    for (anchor in anchors) {
        val dx = anchor.positionMs - meanX
        val dy = anchor.delayMs - meanY
        numerator += dx * dy
        denominator += dx * dx
    }
    val rate = if (denominator == 0.0) 0.0 else numerator / denominator
    return DriftLinearFit(rate = rate, interceptMs = meanY - rate * meanX)
}

/**
 * Fits a line through all anchors, but guards against one bad anchor corrupting the whole rate:
 * with >=3 anchors, if the worst residual exceeds DRIFT_RESIDUAL_OUTLIER_MS, that single anchor is
 * dropped and the rest are refit. If residuals are still too large even then, returns null - a
 * single global rate genuinely doesn't fit this set of anchors well (more likely a real edit/cut
 * difference than measurement noise), so the caller should not force a linear correction.
 */
private fun fitDriftLineRobust(anchors: List<DriftAnchorPoint>): DriftLinearFit? {
    if (anchors.size < 2) return null
    val fit = fitDriftLine(anchors)
    if (anchors.size < 3) return fit

    val residuals = anchors.map { abs(it.delayMs - fit.predictMs(it.positionMs)) }
    if ((residuals.maxOrNull() ?: 0.0) <= DRIFT_RESIDUAL_OUTLIER_MS) return fit

    val worstIndex = residuals.indices.maxByOrNull { residuals[it] } ?: return fit
    val withoutWorst = anchors.filterIndexed { index, _ -> index != worstIndex }
    val refit = fitDriftLine(withoutWorst)
    val refitResiduals = withoutWorst.map { abs(it.delayMs - refit.predictMs(it.positionMs)) }
    return if ((refitResiduals.maxOrNull() ?: 0.0) <= DRIFT_RESIDUAL_OUTLIER_MS) refit else null
}

/**
 * Picks a new flat subtitleDelayUs baseline at the current position per the fitted line, then
 * hands off to the rate + self-anchoring sentinel for forward correction. Never anchors the rate
 * to a position directly: renderer render() positions run on ExoPlayer's internal offset timebase
 * (~1e12us above media time) that app-side positions can't be compared against, so
 * subtitleDelayUsProvider self-anchors in its own timebase on its first call after the rate lands.
 */
private fun PlayerRuntimeController.applyDriftFit(fit: DriftLinearFit, anchor1PositionMs: Long) {
    val applyPositionMs = currentPlaybackPositionMs() ?: anchor1PositionMs
    val newDelayMs = fit.predictMs(applyPositionMs).roundToLong()
        .coerceIn(SUBTITLE_DELAY_MIN_MS.toLong(), SUBTITLE_DELAY_MAX_MS.toLong())
    subtitleDelayUs.set(newDelayMs * 1000L)
    subtitleDelayRateAnchorPositionUs.set(SUBTITLE_DRIFT_ANCHOR_PENDING_US)
    subtitleDelayRateUsPerUs.set(fit.rate)
    _uiState.update { it.copy(subtitleDelayMs = newDelayMs.toInt()) }
    driftLog(
        "applied: rate=${fit.rate} interceptMs=${fit.interceptMs} applyPositionMs=$applyPositionMs " +
            "delayNowMs=$newDelayMs (anchor self-captures at next render)"
    )

    val perTenMinMs = fit.rate * 600_000.0
    val sign = if (perTenMinMs >= 0) "+" else "-"
    val suffix = "drift-corrected (%s%.1fs/10min)".format(sign, abs(perTenMinMs) / 1000.0)
    // Deliberately NOT gated on subtitleAutoSyncStatus still being set — by the time this
    // background calibration finishes (can be minutes after anchor1), the user has very
    // likely already closed the timing dialog, which clears that field. This field is its
    // own independent, dialog-lifecycle-agnostic slot so the confirmation isn't silently lost.
    _uiState.update { it.copy(subtitleDriftCorrectionInfo = suffix) }
}

private suspend fun PlayerRuntimeController.runSubtitleDriftCalibration(
    subtitle: Subtitle,
    internalTrack: TrackInfo,
    anchor1PositionMs: Long,
    anchor1DelayMs: Long,
    plannedTargetPositionsMs: List<Long>,
    useForward: Boolean,
    sessionToken: Int
) {
    var healthAttempt = 0
    while (healthAttempt < DRIFT_BUFFER_HEALTH_CHECK_ATTEMPTS) {
        val timeline = _playbackTimeline.value
        val marginMs = timeline.bufferedPosition - timeline.currentPosition
        if (marginMs >= DRIFT_BUFFER_HEALTH_MIN_MARGIN_MS) break
        healthAttempt++
        delay(DRIFT_BUFFER_HEALTH_CHECK_INTERVAL_MS)
    }
    if (sessionToken != subtitleDriftCalibrationSessionToken) {
        driftLog("abort: session token stale after buffer-health check")
        return
    }

    val durationMs = currentPlaybackDurationMs()
    val anchors = mutableListOf(DriftAnchorPoint(anchor1PositionMs, anchor1DelayMs))
    var appliedAnyFit = false

    for (plannedTarget in plannedTargetPositionsMs) {
        if (anchors.size >= DRIFT_MAX_TOTAL_ANCHORS) break
        if (sessionToken != subtitleDriftCalibrationSessionToken) {
            driftLog("abort: session stale before anchor ${anchors.size + 1}")
            return
        }

        var currentTarget = plannedTarget
        var anchorResult: DriftAnchorPoint? = null
        for (targetAttempt in 1..DRIFT_MAX_TARGET_RETRY_ATTEMPTS) {
            if (sessionToken != subtitleDriftCalibrationSessionToken) {
                driftLog("abort: session stale before target attempt $targetAttempt for anchor ${anchors.size + 1}")
                return
            }
            driftLog(
                "anchor ${anchors.size + 1}: target attempt $targetAttempt/$DRIFT_MAX_TARGET_RETRY_ATTEMPTS " +
                    "at targetPositionMs=$currentTarget"
            )
            anchorResult = tryGatherDriftAnchorAtTarget(
                subtitle = subtitle,
                internalTrack = internalTrack,
                targetPositionMs = currentTarget,
                sessionToken = sessionToken
            )
            if (anchorResult != null) break
            // The chosen point was likely an extended music/no-dialogue stretch - nudge further
            // along and try a nearby spot instead of giving up on this anchor after one unlucky
            // target.
            currentTarget = (if (useForward) {
                currentTarget + DRIFT_TARGET_NUDGE_MS
            } else {
                currentTarget - DRIFT_TARGET_NUDGE_MS
            }).coerceIn(0L, durationMs)
        }
        if (anchorResult == null) {
            // This general area is a dead end even after nudging, but a LATER planned target is a
            // different region entirely and may still work - keep trying the rest of the plan
            // rather than giving up on further anchors altogether.
            driftLog("skip: all target attempts failed for anchor ${anchors.size + 1} at this planned position")
            continue
        }
        if (anchors.any { abs(anchorResult.positionMs - it.positionMs) < DRIFT_MIN_SEPARATION_MS }) {
            driftLog("skip: anchor ${anchors.size + 1} too close to an existing anchor (positionMs=${anchorResult.positionMs})")
            continue
        }

        anchors += anchorResult
        if (sessionToken != subtitleDriftCalibrationSessionToken) {
            driftLog("abort: session stale right after gathering anchor ${anchors.size}")
            return
        }

        val fit = fitDriftLineRobust(anchors)
        if (fit == null || abs(fit.rate) > DRIFT_RATE_MAX_ABS) {
            val reason = if (fit == null) {
                "residuals too large for a single line even after dropping the worst anchor"
            } else {
                "fitted rate ${fit.rate} exceeds max $DRIFT_RATE_MAX_ABS"
            }
            anchors.removeAt(anchors.lastIndex)
            driftLog("discarding anchor at positionMs=${anchorResult.positionMs} ($reason) - keeping ${anchors.size} anchor(s)")
            continue
        }

        driftLog(
            "fit updated with ${anchors.size} anchor(s): rate=${fit.rate} interceptMs=${fit.interceptMs} " +
                "anchors=${anchors.joinToString { a -> "(${a.positionMs},${a.delayMs})" }}"
        )
        if (sessionToken != subtitleDriftCalibrationSessionToken) {
            driftLog("abort: session stale right before apply")
            return
        }
        applyDriftFit(fit, anchor1PositionMs)
        appliedAnyFit = true
    }

    if (!appliedAnyFit) {
        driftLog("abort: no anchor beyond anchor1 produced a usable fit - keeping single-point delay")
    }
}

private suspend fun PlayerRuntimeController.tryGatherDriftAnchorAtTarget(
    subtitle: Subtitle,
    internalTrack: TrackInfo,
    targetPositionMs: Long,
    sessionToken: Int
): DriftAnchorPoint? {
    val cueBuffer = mutableListOf<SubtitleSyncCue>()
    var trackLocked = false
    var totalCueCallbacks = 0
    var emptyCueCallbacks = 0
    var filteredCueCallbacks = 0
    val secondaryTrackSelector = DefaultTrackSelector(context).apply {
        setParameters(
            buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
        )
    }
    // Small bounded buffer window (not the app's normal minutes-ahead buffering) so this only
    // ever fetches a short window of data around the target point, not the whole remaining file.
    val secondaryLoadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(10_000, 15_000, 500, 1_000)
        .build()
    val secondaryPlayer = ExoPlayer.Builder(context, TextOnlyRenderersFactory(context))
        .setTrackSelector(secondaryTrackSelector)
        .setLoadControl(secondaryLoadControl)
        .build()

    try {
        secondaryPlayer.volume = 0f
        secondaryPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                driftLog("player error: ${error.errorCodeName} ${error.message} cause=${error.cause}")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                val stateName = when (playbackState) {
                    androidx.media3.common.Player.STATE_IDLE -> "IDLE"
                    androidx.media3.common.Player.STATE_BUFFERING -> "BUFFERING"
                    androidx.media3.common.Player.STATE_READY -> "READY"
                    androidx.media3.common.Player.STATE_ENDED -> "ENDED"
                    else -> playbackState.toString()
                }
                driftLog("playback state changed: $stateName positionMs=${secondaryPlayer.currentPosition}")
            }

            override fun onCues(cueGroup: CueGroup) {
                totalCueCallbacks++
                if (cueGroup.cues.isEmpty()) {
                    emptyCueCallbacks++
                    return
                }
                val presentationTimeMs = (cueGroup.presentationTimeUs / 1000L).coerceAtLeast(0L)
                val candidateTexts = cueGroup.cues.map { cue -> cue.text?.toString() }
                val text = candidateTexts
                    .mapNotNull { it?.trim()?.takeIf { t -> t.isNotEmpty() } }
                    .firstOrNull { !isNonDialogueMusicCue(it) }
                if (text != null) {
                    cueBuffer += SubtitleSyncCue(
                        startTimeMs = presentationTimeMs,
                        endTimeMs = presentationTimeMs,
                        text = text
                    )
                    return
                }
                filteredCueCallbacks++
                if (filteredCueCallbacks <= 5) {
                    val hasBitmap = cueGroup.cues.any { it.bitmap != null }
                    driftLog(
                        "filtered cue sample #$filteredCueCallbacks at ${presentationTimeMs}ms: " +
                            "cueCount=${cueGroup.cues.size} hasBitmap=$hasBitmap texts=$candidateTexts"
                    )
                }
                // This app's custom Matroska extractor transcodes every subtitle codec (including
                // PGS/VobSub/DVBSub) to a generic mime type during extraction, so `isImageBased`
                // (mime-type based) cannot reliably tell a bitmap-only track apart from a text one
                // - the cue's actual payload (text vs bitmap) is the only trustworthy signal. Mirror
                // anchor1's own OCR fallback here rather than giving up on genuinely bitmap tracks.
                val bitmap = cueGroup.cues.firstNotNullOfOrNull { it.bitmap }
                if (bitmap != null) {
                    scope.launch(Dispatchers.Default) {
                        val recognizedText = runCatching { recognizeSubtitleBitmapText(bitmap) }
                            .getOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() && !isNonDialogueMusicCue(it) }
                            ?: return@launch
                        withContext(Dispatchers.Main) {
                            cueBuffer += SubtitleSyncCue(
                                startTimeMs = presentationTimeMs,
                                endTimeMs = presentationTimeMs,
                                text = recognizedText
                            )
                        }
                    }
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                // Track-identity lock: match the SAME internal subtitle track anchor1 used.
                // NOTE: `isImageBased` (mime-type based) is NOT a trustworthy discriminator here -
                // this app's custom Matroska extractor transcodes every subtitle codec to a generic
                // mime type during extraction, so it can't tell a bitmap track from a text one.
                // Prefer matching by LANGUAGE (mirrors `resolveInternalSubtitleTrackIndexForAutoSync`
                // and is derived from real container metadata, not extractor-internal ordering),
                // only falling back to ordinal position if no language match can be found at all.
                if (trackLocked) return
                var textTrackOrdinal = 0
                var ordinalFallbackGroup: Tracks.Group? = null
                var ordinalFallbackIndex = -1
                for (group in tracks.groups) {
                    if (group.type != C.TRACK_TYPE_TEXT) continue
                    for (i in 0 until group.length) {
                        val format = group.getTrackFormat(i)
                        if (textTrackOrdinal == internalTrack.index) {
                            ordinalFallbackGroup = group
                            ordinalFallbackIndex = i
                        }
                        val languageMatches = internalTrack.language != null &&
                            PlayerSubtitleUtils.matchesLanguageCode(format.language, internalTrack.language)
                        if (languageMatches) {
                            secondaryPlayer.trackSelectionParameters = secondaryPlayer.trackSelectionParameters
                                .buildUpon()
                                .setOverrideForType(TrackSelectionOverride(group.mediaTrackGroup, i))
                                .build()
                            trackLocked = true
                            driftLog(
                                "track locked by language match: ordinal=$textTrackOrdinal id=${format.id} " +
                                    "lang=${format.language}"
                            )
                            return
                        }
                        textTrackOrdinal++
                    }
                }
                if (ordinalFallbackGroup != null && ordinalFallbackIndex >= 0) {
                    secondaryPlayer.trackSelectionParameters = secondaryPlayer.trackSelectionParameters
                        .buildUpon()
                        .setOverrideForType(TrackSelectionOverride(ordinalFallbackGroup.mediaTrackGroup, ordinalFallbackIndex))
                        .build()
                    trackLocked = true
                    driftLog(
                        "track locked by ordinal FALLBACK (no language match found): ordinal=${internalTrack.index} " +
                            "id=${ordinalFallbackGroup.getTrackFormat(ordinalFallbackIndex).id}"
                    )
                    return
                }
                driftLog(
                    "track not found yet this onTracksChanged (wanted lang=${internalTrack.language}/ordinal=${internalTrack.index}, saw $textTrackOrdinal text tracks)"
                )
            }
        })

        // A separate PlayerMediaSourceFactory instance (not the primary's shared one) avoids
        // stomping on the primary's VOD-cache/parallel-connection tracking state.
        val secondaryMediaSourceFactory = PlayerMediaSourceFactory(context.applicationContext)
        val mediaSource = secondaryMediaSourceFactory.createMediaSource(
            context = context,
            url = currentStreamUrl,
            headers = currentHeaders,
            filename = currentFilename,
            responseHeaders = currentStreamResponseHeaders,
            mimeTypeOverride = currentStreamMimeType
        )
        secondaryPlayer.setMediaSource(mediaSource)
        secondaryPlayer.prepare()
        secondaryPlayer.seekTo(targetPositionMs)
        secondaryPlayer.playWhenReady = true
        driftLog(
            "secondary player prepared, seeking to ${targetPositionMs}ms " +
                "host=${runCatching { android.net.Uri.parse(currentStreamUrl).host }.getOrNull()}"
        )

        val pooledOffsetsMs = mutableListOf<Long>()
        var lastCuePositionMs: Long? = null
        for (poolAttempt in 1..DRIFT_ANCHOR_POOL_MAX_ATTEMPTS) {
            if (sessionToken != subtitleDriftCalibrationSessionToken) {
                driftLog("abort: session stale mid-pooling at this target")
                break
            }
            // Each pooling attempt requires a genuinely NEW batch of cues (not re-asking the LLM
            // with identical input, which at temperature 0.0 would just repeat the same answer) —
            // the secondary player keeps playing forward past the first batch, so waiting for
            // poolAttempt * AUTO_SYNC_SOURCE_LINE_COUNT total cues gives real, independent lines
            // for each additional attempt.
            val neededCueCount = poolAttempt * AUTO_SYNC_SOURCE_LINE_COUNT
            val gathered = withTimeoutOrNull(DRIFT_GATHER_TIMEOUT_MS) {
                var waitedMs = 0L
                while (cueBuffer.size < neededCueCount) {
                    delay(250)
                    waitedMs += 250
                    if (waitedMs % 15_000L == 0L) {
                        driftLog(
                            "still waiting (${waitedMs}ms, pool attempt $poolAttempt/$DRIFT_ANCHOR_POOL_MAX_ATTEMPTS): " +
                                "playbackState=${secondaryPlayer.playbackState} positionMs=${secondaryPlayer.currentPosition} " +
                                "bufferedMs=${secondaryPlayer.bufferedPosition} isPlaying=${secondaryPlayer.isPlaying} " +
                                "totalCueCallbacks=$totalCueCallbacks emptyCueCallbacks=$emptyCueCallbacks " +
                                "filteredCueCallbacks=$filteredCueCallbacks"
                        )
                    }
                }
                true
            } ?: false
            driftLog(
                "gather result (pool attempt $poolAttempt/$DRIFT_ANCHOR_POOL_MAX_ATTEMPTS): gathered=$gathered " +
                    "cueCount=${cueBuffer.size} trackLocked=$trackLocked totalCueCallbacks=$totalCueCallbacks " +
                    "emptyCueCallbacks=$emptyCueCallbacks filteredCueCallbacks=$filteredCueCallbacks"
            )
            if (!gathered || sessionToken != subtitleDriftCalibrationSessionToken) {
                // First attempt failing entirely means this target is a dead end (caller retries
                // at a nudged target); a later pooling attempt failing just means we settle for
                // fewer pooled offsets than hoped, not a total failure of this target.
                break
            }

            val batchCues = cueBuffer.takeLast(AUTO_SYNC_SOURCE_LINE_COUNT)
            // referenceDelayCompensationMsOverride = 0L: this secondary player never applies
            // subtitleDelayUs at all, so its cue timestamps are already raw/undelayed — unlike the
            // primary's reference cues, which runSubtitleAutoSyncRequest otherwise compensates for.
            val result = runSubtitleAutoSyncRequest(
                subtitle = subtitle,
                internalCues = batchCues,
                attempt = poolAttempt,
                maxAttempts = DRIFT_ANCHOR_POOL_MAX_ATTEMPTS,
                referenceDelayCompensationMsOverride = 0L
            )
            if (sessionToken != subtitleDriftCalibrationSessionToken) break
            if (result.offsetsMs.isNotEmpty()) {
                pooledOffsetsMs += result.offsetsMs
                lastCuePositionMs = batchCues.last().startTimeMs
            }
            if (pooledOffsetsMs.size >= DRIFT_ANCHOR_POOL_TARGET_OFFSETS) break
        }

        if (pooledOffsetsMs.isEmpty()) {
            driftLog("abort: no LLM matches across pooling attempts at this target")
            return null
        }
        val anchorDelayMs = robustMeanOfLongs(pooledOffsetsMs)
            .coerceIn(SUBTITLE_DELAY_MIN_MS.toLong(), SUBTITLE_DELAY_MAX_MS.toLong())
        return DriftAnchorPoint(positionMs = lastCuePositionMs ?: targetPositionMs, delayMs = anchorDelayMs)
    } finally {
        runCatching { secondaryPlayer.stop() }
        runCatching { secondaryPlayer.clearMediaItems() }
        runCatching { secondaryPlayer.release() }
    }
}

private class TextOnlyRenderersFactory(context: Context) : DefaultRenderersFactory(context) {
    // This player only ever needs subtitle-track cue timestamps for calibration — it never
    // displays video or plays audio, so both renderer types are intentionally left empty (the
    // dominant CPU/resource cost of a normal player is fully avoided by construction).
    override fun buildVideoRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        eventHandler: Handler,
        eventListener: VideoRendererEventListener,
        allowedVideoJoiningTimeMs: Long,
        out: ArrayList<Renderer>
    ) = Unit

    override fun buildAudioRenderers(
        context: Context,
        extensionRendererMode: Int,
        mediaCodecSelector: MediaCodecSelector,
        enableDecoderFallback: Boolean,
        audioSink: AudioSink,
        eventHandler: Handler,
        eventListener: AudioRendererEventListener,
        out: ArrayList<Renderer>
    ) = Unit
}
