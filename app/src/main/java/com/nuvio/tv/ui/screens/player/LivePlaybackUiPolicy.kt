package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.data.local.BufferSettings
import com.nuvio.tv.data.local.PlayerSettings

/**
 * Live TV and VOD both commonly use HLS (.m3u8). The URL is not a reliable
 * discriminator. Use the player's live-window signal (ExoPlayer
 * [androidx.media3.common.Player.isCurrentMediaItemLive], or MPV unseekable)
 * plus the Stremio catalog type `channel`.
 *
 * Do not treat `tv` as live: in this app it is the series synonym.
 */
object LivePlaybackUiPolicy {
    fun isLiveContentType(contentType: String?): Boolean {
        return contentType.equals("channel", ignoreCase = true)
    }

    fun nextLiveLatch(playerReportsLive: Boolean, previouslyLatched: Boolean): Boolean {
        return previouslyLatched || playerReportsLive
    }

    const val LIVE_FORWARD_BUFFER_THRESHOLD_MS = 10_000L
    const val LIVE_FORWARD_BUFFER_MIN_ESCAPE_MS = 1_000L
    const val LIVE_EDGE_BUFFER_MS = 3_000L
    const val LIVE_DEADBAND_MS = 5_000L
    const val LIVE_PAUSE_DELAY_THRESHOLD_MS = 1_000L
    const val LIVE_MIN_BACK_SEEK_SLICE_MS = 10_000L

    fun isLivePlayback(
        playerReportsLive: Boolean,
        contentType: String?,
        latchedLive: Boolean = false
    ): Boolean {
        return latchedLive ||
            playerReportsLive ||
            isLiveContentType(contentType)
    }

    fun calculateLiveProgress(
        currentPosition: Long,
        bufferedPosition: Long,
        maxBufferMs: Long = com.nuvio.tv.data.local.BufferSettings.DEFAULT_MAX_BUFFER_MS.toLong()
    ): Pair<Float, Float> {
        val forwardBufferMs = (bufferedPosition - currentPosition).coerceAtLeast(0L)
        val accumulatedBufferMs = (forwardBufferMs - LIVE_EDGE_BUFFER_MS).coerceAtLeast(0L)
        val bufferWindowMs = maxOf(maxBufferMs, accumulatedBufferMs).coerceAtLeast(1_000L).toFloat()
        return if (accumulatedBufferMs <= LIVE_FORWARD_BUFFER_MIN_ESCAPE_MS) {
            1f to 1f
        } else {
            val progress = (1f - (accumulatedBufferMs.toFloat() / bufferWindowMs)).coerceIn(0f, 1f)
            progress to 1f
        }
    }

    fun shouldSeekToLiveEdge(displayedDelayMs: Long, deltaMs: Long): Boolean {
        if (deltaMs <= 0L) return false
        if (displayedDelayMs <= LIVE_FORWARD_BUFFER_MIN_ESCAPE_MS) return true
        if (displayedDelayMs < LIVE_FORWARD_BUFFER_THRESHOLD_MS) return true
        return (displayedDelayMs - deltaMs) <= LIVE_FORWARD_BUFFER_MIN_ESCAPE_MS
    }

    /**
     * True live-edge cushion from player positions / live offset only.
     * Overlay delay must not be added on top — that invents a future timestamp
     * and ExoPlayer answers by seeking to the default live position (buffer wipe).
     */
    fun liveEdgePositionMs(
        currentPosition: Long,
        bufferedPosition: Long,
        displayedDelayMs: Long = 0L,
        rawDelayMs: Long = -1L
    ): Long {
        val fromBuffered = (bufferedPosition - currentPosition - LIVE_EDGE_BUFFER_MS).coerceAtLeast(0L)
        val effectiveDelayMs = maxOf(fromBuffered, rawDelayMs.coerceAtLeast(0L))
        return currentPosition + effectiveDelayMs
    }

    fun calculateLiveSeekTarget(
        currentPosition: Long,
        bufferedPosition: Long,
        deltaMs: Long,
        isBackBufferEnabled: Boolean,
        backBufferDurationMs: Long = 0L,
        displayedDelayMs: Long? = null,
        rawDelayMs: Long = -1L
    ): Long? {
        if (deltaMs < 0L) {
            if (!isBackBufferEnabled) return null
            val minPosition = (currentPosition - backBufferDurationMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            val availableBackBufferMs = currentPosition - minPosition
            if (availableBackBufferMs < LIVE_MIN_BACK_SEEK_SLICE_MS) return null
            return (currentPosition + deltaMs).coerceAtLeast(minPosition)
        } else if (deltaMs > 0L) {
            val fromBuffered = (bufferedPosition - currentPosition - LIVE_EDGE_BUFFER_MS).coerceAtLeast(0L)
            val rawMs = maxOf(fromBuffered, rawDelayMs.coerceAtLeast(0L))
            val liveEdgePos = currentPosition + rawMs
            val uiDelayMs = displayedDelayMs
            if (uiDelayMs != null) {
                if (shouldSeekToLiveEdge(uiDelayMs, deltaMs)) {
                    return liveEdgePos
                }
                val hiddenGapMs = (rawMs - uiDelayMs).coerceAtLeast(0L)
                val target = currentPosition + deltaMs + hiddenGapMs
                return if (rawMs > 0L) target.coerceAtMost(liveEdgePos) else target
            }
            return if (fromBuffered < LIVE_FORWARD_BUFFER_THRESHOLD_MS) {
                if (fromBuffered > LIVE_FORWARD_BUFFER_MIN_ESCAPE_MS) {
                    liveEdgePos
                } else {
                    bufferedPosition
                }
            } else {
                (currentPosition + deltaMs).coerceAtMost(liveEdgePos)
            }
        } else {
            return currentPosition
        }
    }

    fun calculateLiveSeekToTarget(
        currentPosition: Long,
        bufferedPosition: Long,
        targetPosition: Long,
        isBackBufferEnabled: Boolean,
        backBufferDurationMs: Long = 0L,
        displayedDelayMs: Long? = null,
        rawDelayMs: Long = -1L
    ): Long? {
        if (targetPosition < currentPosition) {
            if (!isBackBufferEnabled) return null
            val minPosition = (currentPosition - backBufferDurationMs.coerceAtLeast(0L)).coerceAtLeast(0L)
            val availableBackBufferMs = currentPosition - minPosition
            if (availableBackBufferMs < LIVE_MIN_BACK_SEEK_SLICE_MS) return null
            return targetPosition.coerceAtLeast(minPosition)
        } else if (targetPosition > currentPosition) {
            val deltaMs = targetPosition - currentPosition
            return calculateLiveSeekTarget(
                currentPosition = currentPosition,
                bufferedPosition = bufferedPosition,
                deltaMs = deltaMs,
                isBackBufferEnabled = isBackBufferEnabled,
                backBufferDurationMs = backBufferDurationMs,
                displayedDelayMs = displayedDelayMs,
                rawDelayMs = rawDelayMs
            )
        } else {
            return currentPosition
        }
    }

    /**
     * Max / back-buffer durations that the live timeline UI should use so the
     * progress window matches the LoadControl that will actually be built.
     */
    fun configuredBufferDurationsForLiveUi(settings: PlayerSettings): Pair<Int, Int> {
        val customBuffers = settings.bufferEngineEnabled
        val maxMs = when {
            settings.nuvioPerformanceModeEnabled && customBuffers -> settings.bufferSettings.maxBufferMs
            settings.nuvioPerformanceModeEnabled -> NuvioExoPlayerPerformanceHelper.DEFAULT_NUVIO_MAX_BUFFER_MS
            customBuffers -> settings.bufferSettings.maxBufferMs
            else -> BufferSettings.DEFAULT_MAX_BUFFER_MS
        }
        val backMs = when {
            settings.nuvioPerformanceModeEnabled && customBuffers -> settings.bufferSettings.backBufferDurationMs
            settings.nuvioPerformanceModeEnabled -> NuvioExoPlayerPerformanceHelper.DEFAULT_NUVIO_BACK_BUFFER_MS
            customBuffers -> settings.bufferSettings.backBufferDurationMs
            else -> 0
        }
        return maxMs to backMs
    }

    /**
     * Forward delay above the live-edge cushion. Do not use total buffered
     * duration here — that is buffer size, not distance behind live, and it
     * makes seeks overshoot the live window.
     */
    fun rawAccumulatedDelayMs(
        currentPosition: Long,
        bufferedPosition: Long,
        liveOffsetMs: Long = -1L,
        totalBufferedMs: Long = -1L
    ): Long {
        val fromBuffered = (bufferedPosition - currentPosition - LIVE_EDGE_BUFFER_MS).coerceAtLeast(0L)
        val fromOffset = if (liveOffsetMs > 0L) {
            (liveOffsetMs - LIVE_EDGE_BUFFER_MS).coerceAtLeast(0L)
        } else {
            0L
        }
        return maxOf(fromBuffered, fromOffset)
    }
}

/** Accumulates wall-clock time spent actually playing a live stream. */
class LivePlaybackWatchClock {
    private var accumulatedMs = 0L
    private var segmentStartedAtElapsedMs: Long? = null

    fun reset() {
        accumulatedMs = 0L
        segmentStartedAtElapsedMs = null
    }

    fun watchedDurationMs(
        isLive: Boolean,
        isPlaying: Boolean,
        nowElapsedMs: Long
    ): Long {
        if (!isLive) {
            if (accumulatedMs != 0L || segmentStartedAtElapsedMs != null) {
                reset()
            }
            return 0L
        }
        if (isPlaying) {
            if (segmentStartedAtElapsedMs == null) {
                segmentStartedAtElapsedMs = nowElapsedMs
            }
        } else {
            val started = segmentStartedAtElapsedMs
            if (started != null) {
                accumulatedMs += (nowElapsedMs - started).coerceAtLeast(0L)
                segmentStartedAtElapsedMs = null
            }
        }
        val running = segmentStartedAtElapsedMs
            ?.let { (nowElapsedMs - it).coerceAtLeast(0L) }
            ?: 0L
        return accumulatedMs + running
    }
}

/**
 * Anti-jitter filter for the live progress bar and LIVE / LIVE -mm:ss clock.
 *
 * While playing at the live edge, HLS chunk arrivals are ignored so the UI stays
 * locked on LIVE. While paused, delay follows wall-clock at 1s/s (no chunk jumps).
 * Progress and delay always come from the same displayed delay.
 */
class LivePlaybackBufferFilter(
    private val liveEdgeThresholdMs: Long = LivePlaybackUiPolicy.LIVE_EDGE_BUFFER_MS,
    private val deadbandMs: Long = LivePlaybackUiPolicy.LIVE_DEADBAND_MS,
    private val liveEdgeBufferBaselineMs: Long = LivePlaybackUiPolicy.LIVE_EDGE_BUFFER_MS,
    private val pauseDelayThresholdMs: Long = LivePlaybackUiPolicy.LIVE_PAUSE_DELAY_THRESHOLD_MS,
    private val maxSlewRateMsPerSecond: Long = 1_000L
) {
    private var isLockedToLive: Boolean = true
    private var filteredDelayMs: Long = 0L
    private var lastReportedProgress: Float = 1f
    private var lastUpdateTimeMs: Long? = null
    private var peakRawDelayMs: Long = 0L
    private var peakRawAtElapsedMs: Long? = null

    val lockedToLive: Boolean get() = isLockedToLive

    val displayedDelayMs: Long
        get() = if (isLockedToLive) 0L else filteredDelayMs.coerceAtLeast(pauseDelayThresholdMs)

    fun reset() {
        isLockedToLive = true
        filteredDelayMs = 0L
        lastReportedProgress = 1f
        lastUpdateTimeMs = null
        peakRawDelayMs = 0L
        peakRawAtElapsedMs = null
    }

    fun snapToLive() {
        isLockedToLive = true
        filteredDelayMs = 0L
        lastReportedProgress = 1f
        lastUpdateTimeMs = null
        peakRawDelayMs = 0L
        peakRawAtElapsedMs = null
    }

    fun unlockFromLive() {
        isLockedToLive = false
    }

    /**
     * Moves the overlay clock/bar by [deltaMs] without reading the real forward
     * buffer. Forward reduces displayed delay; the player seek mapping consumes
     * any hidden slew gap so the two meet at the new displayed delay.
     */
    fun applySeekDelta(
        deltaMs: Long,
        maxBufferMs: Long,
        nowElapsedMs: Long = -1L
    ): Pair<Long, Float> {
        // Keep the pause clock on the same timeline as update(). A mismatched
        // clock here (nanoTime vs elapsedRealtime) freezes slew at 0ms/tick.
        if (nowElapsedMs >= 0L) {
            lastUpdateTimeMs = nowElapsedMs
        }
        if (deltaMs == 0L) {
            return displayState(maxBufferMs)
        }
        if (deltaMs > 0L) {
            if (!isLockedToLive) {
                filteredDelayMs = (filteredDelayMs - deltaMs).coerceAtLeast(0L)
                if (filteredDelayMs <= pauseDelayThresholdMs) {
                    isLockedToLive = true
                    filteredDelayMs = 0L
                }
            }
        } else {
            isLockedToLive = false
            filteredDelayMs = (filteredDelayMs - deltaMs).coerceAtLeast(0L)
        }
        peakRawDelayMs = filteredDelayMs
        peakRawAtElapsedMs = lastUpdateTimeMs
        return displayState(maxBufferMs)
    }

    fun update(
        currentPosition: Long,
        bufferedPosition: Long,
        isPlaying: Boolean,
        maxBufferMs: Long,
        isSeeking: Boolean = false,
        nowElapsedMs: Long = -1L,
        rawDelayMs: Long = -1L
    ): Pair<Long, Float> {
        val now = if (nowElapsedMs >= 0L) nowElapsedMs else (System.nanoTime() / 1_000_000L)
        val prevTime = lastUpdateTimeMs
        val elapsedSinceLastUpdateMs = if (prevTime != null && now > prevTime) now - prevTime else 0L
        lastUpdateTimeMs = now

        val fromPositions = (bufferedPosition - currentPosition - liveEdgeBufferBaselineMs).coerceAtLeast(0L)
        val rawAccumulatedMs = if (rawDelayMs >= 0L) maxOf(fromPositions, rawDelayMs) else fromPositions

        if (isSeeking) {
            // Keep the overlay delay. Raw buffer is mapped onto the player seek
            // target instead of jumping the bar/clock.
            return displayState(maxBufferMs)
        }

        if (!isPlaying) {
            // Pause: 1s/s wall-clock. HLS raw delay arrives in stalls/jumps, so
            // extrapolate from the last peak — a stalled 21s report must not freeze
            // the bar while live keeps moving. Chunk spikes still only catch up at 1s/s.
            val maxAllowedStepMs = (elapsedSinceLastUpdateMs * maxSlewRateMsPerSecond) / 1_000L
            val estimatedRawMs = estimatedPausedRawDelayMs(rawAccumulatedMs, now)
            if (filteredDelayMs < estimatedRawMs) {
                filteredDelayMs = (filteredDelayMs + maxAllowedStepMs).coerceAtMost(estimatedRawMs)
            }

            if (isLockedToLive && filteredDelayMs >= pauseDelayThresholdMs) {
                isLockedToLive = false
            }
            return displayState(maxBufferMs)
        }

        if (isLockedToLive) {
            filteredDelayMs = 0L
        } else if (filteredDelayMs <= pauseDelayThresholdMs) {
            isLockedToLive = true
            filteredDelayMs = 0L
        }
        return displayState(maxBufferMs)
    }

    private fun estimatedPausedRawDelayMs(reportedRawMs: Long, nowElapsedMs: Long): Long {
        if (reportedRawMs > peakRawDelayMs) {
            peakRawDelayMs = reportedRawMs
            peakRawAtElapsedMs = nowElapsedMs
            return reportedRawMs
        }
        if (peakRawDelayMs <= 0L) {
            peakRawAtElapsedMs = nowElapsedMs
            return reportedRawMs
        }
        val peakAt = peakRawAtElapsedMs ?: nowElapsedMs
        val extrapolatedMs = peakRawDelayMs + (nowElapsedMs - peakAt).coerceAtLeast(0L)
        return maxOf(reportedRawMs, extrapolatedMs)
    }

    private fun displayState(maxBufferMs: Long): Pair<Long, Float> {
        val displayDelay = if (isLockedToLive) {
            0L
        } else {
            filteredDelayMs.coerceAtLeast(pauseDelayThresholdMs)
        }
        val bufferWindowMs = maxOf(maxBufferMs, displayDelay).coerceAtLeast(1_000L).toFloat()
        lastReportedProgress = if (displayDelay == 0L) {
            1f
        } else {
            (1f - (displayDelay.toFloat() / bufferWindowMs)).coerceIn(0f, 1f)
        }
        return displayDelay to lastReportedProgress
    }
}

