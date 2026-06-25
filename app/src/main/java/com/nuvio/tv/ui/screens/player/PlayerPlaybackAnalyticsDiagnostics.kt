package com.nuvio.tv.ui.screens.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.DecoderReuseEvaluation
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackAnalyticsInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackEventInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackFormatInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackLoadErrorInput
import com.nuvio.tv.data.repository.PlaybackIssuePlaybackLoadInput

private const val PLAYBACK_ANALYTICS_EVENT_LIMIT = 140
private const val POSITION_STALL_THRESHOLD_MS = 5_000L
private const val POSITION_PROGRESS_EPSILON_MS = 250L

internal class PlayerPlaybackAnalyticsDiagnostics {
    private var sessionStartedAtElapsedMs: Long = SystemClock.elapsedRealtime()
    private var sessionStartedAtWallTimeMs: Long = System.currentTimeMillis()
    private val events: ArrayDeque<PlaybackIssuePlaybackEventInput> = ArrayDeque()

    private var eventCount: Int = 0
    private var playbackState: Int? = null
    private var playbackStateName: String? = null
    private var playWhenReady: Boolean? = null
    private var isPlaying: Boolean? = null
    private var isLoading: Boolean? = null
    private var positionMs: Long? = null
    private var bufferedPositionMs: Long? = null
    private var durationMs: Long? = null
    private var bufferedPercentage: Int? = null
    private var firstFrameElapsedMs: Long? = null
    private var renderedFirstFrameCount: Int = 0

    private var lastPositionForStallMs: Long = -1L
    private var positionLastAdvancedAtMs: Long = sessionStartedAtElapsedMs
    private var positionStallActive: Boolean = false
    private var positionStallCount: Int = 0
    private var longestPositionStallMs: Long = 0L

    private var droppedFrames: Int = 0
    private var maxDroppedFramesInEvent: Int = 0
    private var videoDecoderName: String? = null
    private var videoDecoderInitMs: Long? = null
    private var videoDecoderReleaseCount: Int = 0
    private var videoRenderedOutputBuffers: Int? = null
    private var videoDroppedBuffers: Int? = null
    private var videoMaxConsecutiveDroppedBuffers: Int? = null
    private var videoFrameProcessingOffsetTotalUs: Long = 0L
    private var videoFrameProcessingOffsetCount: Int = 0
    private var videoFormat: PlaybackIssuePlaybackFormatInput? = null

    private var audioDecoderName: String? = null
    private var audioDecoderInitMs: Long? = null
    private var audioDecoderReleaseCount: Int = 0
    private var audioUnderrunCount: Int = 0
    private var audioUnderrunBufferSize: Int? = null
    private var audioUnderrunBufferSizeMs: Long? = null
    private var audioUnderrunElapsedSinceLastFeedMs: Long? = null
    private var audioFormat: PlaybackIssuePlaybackFormatInput? = null

    private var bandwidthEstimateBps: Long? = null
    private var bandwidthTransferDurationMs: Int? = null
    private var bandwidthBytesTransferred: Long? = null

    private var loadStartedCount: Int = 0
    private var loadCompletedCount: Int = 0
    private var loadCanceledCount: Int = 0
    private var loadErrorCount: Int = 0
    private var totalBytesLoaded: Long = 0L
    private var lastLoad: PlaybackIssuePlaybackLoadInput? = null
    private var lastLoadError: PlaybackIssuePlaybackLoadErrorInput? = null

    fun reset() {
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        sessionStartedAtWallTimeMs = System.currentTimeMillis()
        events.clear()
        eventCount = 0
        playbackState = null
        playbackStateName = null
        playWhenReady = null
        isPlaying = null
        isLoading = null
        positionMs = null
        bufferedPositionMs = null
        durationMs = null
        bufferedPercentage = null
        firstFrameElapsedMs = null
        renderedFirstFrameCount = 0
        lastPositionForStallMs = -1L
        positionLastAdvancedAtMs = sessionStartedAtElapsedMs
        positionStallActive = false
        positionStallCount = 0
        longestPositionStallMs = 0L
        droppedFrames = 0
        maxDroppedFramesInEvent = 0
        videoDecoderName = null
        videoDecoderInitMs = null
        videoDecoderReleaseCount = 0
        videoRenderedOutputBuffers = null
        videoDroppedBuffers = null
        videoMaxConsecutiveDroppedBuffers = null
        videoFrameProcessingOffsetTotalUs = 0L
        videoFrameProcessingOffsetCount = 0
        videoFormat = null
        audioDecoderName = null
        audioDecoderInitMs = null
        audioDecoderReleaseCount = 0
        audioUnderrunCount = 0
        audioUnderrunBufferSize = null
        audioUnderrunBufferSizeMs = null
        audioUnderrunElapsedSinceLastFeedMs = null
        audioFormat = null
        bandwidthEstimateBps = null
        bandwidthTransferDurationMs = null
        bandwidthBytesTransferred = null
        loadStartedCount = 0
        loadCompletedCount = 0
        loadCanceledCount = 0
        loadErrorCount = 0
        totalBytesLoaded = 0L
        lastLoad = null
        lastLoadError = null
    }

    fun recordProgressSnapshot(player: Player, hasRenderedFirstFrame: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val position = player.currentPosition.coerceAtLeast(0L)
        playbackState = player.playbackState
        playbackStateName = player.playbackState.playbackStateName()
        playWhenReady = player.playWhenReady
        isPlaying = player.isPlaying
        isLoading = player.isLoading
        positionMs = position
        bufferedPositionMs = player.bufferedPosition.coerceAtLeast(position)
        durationMs = player.duration.takeIf { it > 0L }
        bufferedPercentage = player.bufferedPercentage.takeIf { it >= 0 }

        val shouldDetectStall = hasRenderedFirstFrame &&
            player.playWhenReady &&
            player.isPlaying &&
            player.playbackState == Player.STATE_READY
        if (!shouldDetectStall) {
            lastPositionForStallMs = position
            positionLastAdvancedAtMs = now
            positionStallActive = false
            return
        }

        if (lastPositionForStallMs < 0L || position > lastPositionForStallMs + POSITION_PROGRESS_EPSILON_MS) {
            if (positionStallActive) {
                record(
                    name = "position_stall_recovered",
                    eventTime = null,
                    details = details(
                        "stallMs" to (now - positionLastAdvancedAtMs).coerceAtLeast(0L).toString(),
                        "positionMs" to position.toString()
                    )
                )
            }
            lastPositionForStallMs = position
            positionLastAdvancedAtMs = now
            positionStallActive = false
            return
        }

        val stalledForMs = (now - positionLastAdvancedAtMs).coerceAtLeast(0L)
        if (stalledForMs >= POSITION_STALL_THRESHOLD_MS) {
            longestPositionStallMs = maxOf(longestPositionStallMs, stalledForMs)
            if (!positionStallActive) {
                positionStallActive = true
                positionStallCount += 1
                record(
                    name = "position_stall",
                    eventTime = null,
                    details = details(
                        "stallMs" to stalledForMs.toString(),
                        "positionMs" to position.toString(),
                        "bufferedPositionMs" to player.bufferedPosition.coerceAtLeast(0L).toString(),
                        "state" to player.playbackState.playbackStateName()
                    )
                )
            }
        }
    }

    fun onPlaybackStateChanged(eventTime: AnalyticsListener.EventTime, state: Int) {
        playbackState = state
        playbackStateName = state.playbackStateName()
        positionMs = eventTime.currentPlaybackPositionMs.safeTimeMs()
        bufferedPositionMs = eventTime.bufferedPositionMs()
        record(
            name = "playback_state",
            eventTime = eventTime,
            details = details("state" to state.playbackStateName())
        )
    }

    fun onPlayWhenReadyChanged(eventTime: AnalyticsListener.EventTime, ready: Boolean, reason: Int) {
        playWhenReady = ready
        record(
            name = "play_when_ready",
            eventTime = eventTime,
            details = details("ready" to ready.toString(), "reason" to reason.toString())
        )
    }

    fun onIsPlayingChanged(eventTime: AnalyticsListener.EventTime, playing: Boolean) {
        isPlaying = playing
        record(
            name = "is_playing",
            eventTime = eventTime,
            details = details("playing" to playing.toString())
        )
    }

    fun onIsLoadingChanged(eventTime: AnalyticsListener.EventTime, loading: Boolean) {
        isLoading = loading
        record(
            name = "is_loading",
            eventTime = eventTime,
            details = details("loading" to loading.toString())
        )
    }

    fun onRenderedFirstFrame(eventTime: AnalyticsListener.EventTime) {
        renderedFirstFrameCount += 1
        if (firstFrameElapsedMs == null) {
            firstFrameElapsedMs = (SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs).coerceAtLeast(0L)
        }
        record(name = "rendered_first_frame", eventTime = eventTime)
    }

    fun onSyntheticFirstFrame(player: Player?) {
        renderedFirstFrameCount += 1
        if (firstFrameElapsedMs == null) {
            firstFrameElapsedMs = (SystemClock.elapsedRealtime() - sessionStartedAtElapsedMs).coerceAtLeast(0L)
        }
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
        record(
            name = "first_frame_ready",
            eventTime = null,
            details = details("source" to "state_ready")
        )
    }

    fun onRebufferStarted(player: Player?, count: Int) {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
        record(
            name = "rebuffer_start",
            eventTime = null,
            details = details(
                "count" to count.toString(),
                "positionMs" to (player?.currentPosition?.coerceAtLeast(0L)?.toString())
            )
        )
    }

    fun onRebufferEnded(player: Player?, totalMs: Long, lastDurationMs: Long) {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = true) }
        record(
            name = "rebuffer_end",
            eventTime = null,
            details = details(
                "lastDurationMs" to lastDurationMs.coerceAtLeast(0L).toString(),
                "totalMs" to totalMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializationDurationMs: Long
    ) {
        videoDecoderName = decoderName
        videoDecoderInitMs = initializationDurationMs.coerceAtLeast(0L)
        record(
            name = "video_decoder_initialized",
            eventTime = eventTime,
            details = details("decoder" to decoderName, "initMs" to videoDecoderInitMs?.toString())
        )
    }

    fun onVideoDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        videoDecoderReleaseCount += 1
        record(
            name = "video_decoder_released",
            eventTime = eventTime,
            details = details("decoder" to decoderName, "count" to videoDecoderReleaseCount.toString())
        )
    }

    fun onVideoInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        reuseEvaluation: DecoderReuseEvaluation?
    ) {
        videoFormat = format.toPlaybackFormat(
            trackType = C.TRACK_TYPE_VIDEO.trackTypeName(),
            support = videoFormat?.support,
            reuseEvaluation = reuseEvaluation
        )
        record(
            name = "video_format",
            eventTime = eventTime,
            details = format.formatDetails(C.TRACK_TYPE_VIDEO.trackTypeName())
        )
    }

    fun onVideoTrackSnapshot(format: Format, support: String?, selected: Boolean) {
        videoFormat = format.toPlaybackFormat(
            trackType = C.TRACK_TYPE_VIDEO.trackTypeName(),
            support = support,
            reuseEvaluation = null
        )
        record(
            name = "video_track_snapshot",
            eventTime = null,
            details = format.formatDetails(C.TRACK_TYPE_VIDEO.trackTypeName()) + details(
                "support" to support,
                "selected" to selected.toString()
            )
        )
    }

    fun onVideoSizeChanged(eventTime: AnalyticsListener.EventTime, videoSize: VideoSize) {
        record(
            name = "video_size",
            eventTime = eventTime,
            details = details(
                "width" to videoSize.width.takeIf { it > 0 }?.toString(),
                "height" to videoSize.height.takeIf { it > 0 }?.toString()
            )
        )
    }

    fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, count: Int, elapsedMs: Long) {
        droppedFrames += count.coerceAtLeast(0)
        maxDroppedFramesInEvent = maxOf(maxDroppedFramesInEvent, count.coerceAtLeast(0))
        record(
            name = "dropped_video_frames",
            eventTime = eventTime,
            details = details(
                "count" to count.coerceAtLeast(0).toString(),
                "total" to droppedFrames.toString(),
                "elapsedMs" to elapsedMs.coerceAtLeast(0L).toString()
            )
        )
    }

    fun onVideoFrameProcessingOffset(
        eventTime: AnalyticsListener.EventTime,
        totalProcessingOffsetUs: Long,
        frameCount: Int
    ) {
        if (frameCount > 0) {
            videoFrameProcessingOffsetTotalUs += totalProcessingOffsetUs
            videoFrameProcessingOffsetCount += frameCount
        }
        record(
            name = "video_frame_processing_offset",
            eventTime = eventTime,
            details = details(
                "totalUs" to totalProcessingOffsetUs.toString(),
                "frameCount" to frameCount.coerceAtLeast(0).toString()
            )
        )
    }

    fun onVideoDisabled(eventTime: AnalyticsListener.EventTime, counters: DecoderCounters) {
        counters.ensureUpdated()
        videoRenderedOutputBuffers = counters.renderedOutputBufferCount
        videoDroppedBuffers = counters.droppedBufferCount
        videoMaxConsecutiveDroppedBuffers = counters.maxConsecutiveDroppedBufferCount
        if (counters.videoFrameProcessingOffsetCount > 0) {
            videoFrameProcessingOffsetTotalUs = counters.totalVideoFrameProcessingOffsetUs
            videoFrameProcessingOffsetCount = counters.videoFrameProcessingOffsetCount
        }
        record(
            name = "video_disabled",
            eventTime = eventTime,
            details = details(
                "renderedOutput" to counters.renderedOutputBufferCount.toString(),
                "dropped" to counters.droppedBufferCount.toString(),
                "maxConsecutiveDropped" to counters.maxConsecutiveDroppedBufferCount.toString()
            )
        )
    }

    fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializationDurationMs: Long
    ) {
        audioDecoderName = decoderName
        audioDecoderInitMs = initializationDurationMs.coerceAtLeast(0L)
        record(
            name = "audio_decoder_initialized",
            eventTime = eventTime,
            details = details("decoder" to decoderName, "initMs" to audioDecoderInitMs?.toString())
        )
    }

    fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {
        audioDecoderReleaseCount += 1
        record(
            name = "audio_decoder_released",
            eventTime = eventTime,
            details = details("decoder" to decoderName, "count" to audioDecoderReleaseCount.toString())
        )
    }

    fun onAudioInputFormatChanged(
        eventTime: AnalyticsListener.EventTime,
        format: Format,
        reuseEvaluation: DecoderReuseEvaluation?
    ) {
        audioFormat = format.toPlaybackFormat(
            trackType = C.TRACK_TYPE_AUDIO.trackTypeName(),
            support = null,
            reuseEvaluation = reuseEvaluation
        )
        record(
            name = "audio_format",
            eventTime = eventTime,
            details = format.formatDetails(C.TRACK_TYPE_AUDIO.trackTypeName())
        )
    }

    fun onAudioUnderrun(
        eventTime: AnalyticsListener.EventTime,
        bufferSize: Int,
        bufferSizeMs: Long,
        elapsedSinceLastFeedMs: Long
    ) {
        audioUnderrunCount += 1
        audioUnderrunBufferSize = bufferSize.takeIf { it >= 0 }
        audioUnderrunBufferSizeMs = bufferSizeMs.coerceAtLeast(0L)
        audioUnderrunElapsedSinceLastFeedMs = elapsedSinceLastFeedMs.coerceAtLeast(0L)
        record(
            name = "audio_underrun",
            eventTime = eventTime,
            details = details(
                "count" to audioUnderrunCount.toString(),
                "bufferSize" to audioUnderrunBufferSize?.toString(),
                "bufferSizeMs" to audioUnderrunBufferSizeMs?.toString(),
                "elapsedSinceLastFeedMs" to audioUnderrunElapsedSinceLastFeedMs?.toString()
            )
        )
    }

    fun onBandwidthEstimate(
        eventTime: AnalyticsListener.EventTime,
        totalLoadTimeMs: Int,
        totalBytesLoaded: Long,
        bitrateEstimate: Long
    ) {
        bandwidthTransferDurationMs = totalLoadTimeMs.takeIf { it >= 0 }
        bandwidthBytesTransferred = totalBytesLoaded.coerceAtLeast(0L)
        bandwidthEstimateBps = bitrateEstimate.takeIf { it > 0L }
        record(
            name = "bandwidth_estimate",
            eventTime = eventTime,
            details = details(
                "bitrateBps" to bandwidthEstimateBps?.toString(),
                "bytes" to bandwidthBytesTransferred?.toString(),
                "durationMs" to bandwidthTransferDurationMs?.toString()
            )
        )
    }

    fun onLoadStarted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        loadStartedCount += 1
        lastLoad = loadEventInfo.toPlaybackLoad(mediaLoadData)
        record(
            name = "load_started",
            eventTime = eventTime,
            details = loadDetails(lastLoad)
        )
    }

    fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        loadCompletedCount += 1
        totalBytesLoaded += loadEventInfo.bytesLoaded.coerceAtLeast(0L)
        lastLoad = loadEventInfo.toPlaybackLoad(mediaLoadData)
        record(
            name = "load_completed",
            eventTime = eventTime,
            details = loadDetails(lastLoad)
        )
    }

    fun onLoadCanceled(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData
    ) {
        loadCanceledCount += 1
        lastLoad = loadEventInfo.toPlaybackLoad(mediaLoadData)
        record(
            name = "load_canceled",
            eventTime = eventTime,
            details = loadDetails(lastLoad)
        )
    }

    fun onLoadError(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: LoadEventInfo,
        mediaLoadData: MediaLoadData,
        error: java.io.IOException,
        wasCanceled: Boolean
    ) {
        loadErrorCount += 1
        val load = loadEventInfo.toPlaybackLoad(mediaLoadData)
        lastLoad = load
        lastLoadError = PlaybackIssuePlaybackLoadErrorInput(
            host = load.host,
            dataType = load.dataType,
            trackType = load.trackType,
            exceptionClass = error.javaClass.name,
            message = error.message,
            httpStatus = error.findHttpStatus(),
            wasCanceled = wasCanceled,
            bytesLoaded = load.bytesLoaded,
            durationMs = load.durationMs
        )
        record(
            name = "load_error",
            eventTime = eventTime,
            details = loadDetails(load) + details(
                "exception" to error.javaClass.name,
                "message" to error.message,
                "httpStatus" to error.findHttpStatus()?.toString(),
                "wasCanceled" to wasCanceled.toString()
            )
        )
    }

    fun onPlayerError(eventTime: AnalyticsListener.EventTime, error: PlaybackException) {
        record(
            name = "player_error",
            eventTime = eventTime,
            details = details(
                "errorCode" to error.errorCode.toString(),
                "errorCodeName" to error.errorCodeName,
                "exception" to error.javaClass.name,
                "cause" to error.cause?.javaClass?.name,
                "message" to error.message
            )
        )
    }

    fun snapshot(
        player: Player?,
        hasRenderedFirstFrame: Boolean,
        rebufferCount: Int,
        rebufferTotalMs: Long,
        rebufferStartedAtMs: Long
    ): PlaybackIssuePlaybackAnalyticsInput {
        player?.let { recordProgressSnapshot(it, hasRenderedFirstFrame = hasRenderedFirstFrame) }
        val now = SystemClock.elapsedRealtime()
        val capturedAtMs = System.currentTimeMillis()
        val currentRebufferMs = if (rebufferStartedAtMs > 0L) {
            (now - rebufferStartedAtMs).coerceAtLeast(0L)
        } else {
            0L
        }
        return PlaybackIssuePlaybackAnalyticsInput(
            schemaVersion = 1,
            sessionStartedAtMs = sessionStartedAtWallTimeMs,
            capturedAtMs = capturedAtMs,
            elapsedMs = (now - sessionStartedAtElapsedMs).coerceAtLeast(0L),
            eventCount = eventCount,
            lastEventElapsedMs = events.lastOrNull()?.elapsedMs,
            playbackState = playbackState,
            playbackStateName = playbackStateName,
            playWhenReady = playWhenReady,
            isPlaying = isPlaying,
            isLoading = isLoading,
            positionMs = positionMs,
            bufferedPositionMs = bufferedPositionMs,
            durationMs = durationMs,
            bufferedPercentage = bufferedPercentage,
            firstFrameElapsedMs = firstFrameElapsedMs,
            renderedFirstFrameCount = renderedFirstFrameCount,
            rebufferCount = rebufferCount.coerceAtLeast(0),
            rebufferTotalMs = rebufferTotalMs.coerceAtLeast(0L),
            currentRebufferMs = currentRebufferMs,
            positionStallCount = positionStallCount,
            longestPositionStallMs = longestPositionStallMs,
            droppedFrames = droppedFrames,
            maxDroppedFramesInEvent = maxDroppedFramesInEvent,
            videoDecoderName = videoDecoderName,
            videoDecoderInitMs = videoDecoderInitMs,
            videoDecoderReleaseCount = videoDecoderReleaseCount,
            videoRenderedOutputBuffers = videoRenderedOutputBuffers,
            videoDroppedBuffers = videoDroppedBuffers,
            videoMaxConsecutiveDroppedBuffers = videoMaxConsecutiveDroppedBuffers,
            videoFrameProcessingOffsetAverageUs = videoFrameProcessingOffsetAverageUs(),
            videoFormat = videoFormat,
            audioDecoderName = audioDecoderName,
            audioDecoderInitMs = audioDecoderInitMs,
            audioDecoderReleaseCount = audioDecoderReleaseCount,
            audioUnderrunCount = audioUnderrunCount,
            audioUnderrunBufferSize = audioUnderrunBufferSize,
            audioUnderrunBufferSizeMs = audioUnderrunBufferSizeMs,
            audioUnderrunElapsedSinceLastFeedMs = audioUnderrunElapsedSinceLastFeedMs,
            audioFormat = audioFormat,
            bandwidthEstimateBps = bandwidthEstimateBps,
            bandwidthTransferDurationMs = bandwidthTransferDurationMs,
            bandwidthBytesTransferred = bandwidthBytesTransferred,
            loadStartedCount = loadStartedCount,
            loadCompletedCount = loadCompletedCount,
            loadCanceledCount = loadCanceledCount,
            loadErrorCount = loadErrorCount,
            totalBytesLoaded = totalBytesLoaded.coerceAtLeast(0L),
            lastLoad = lastLoad,
            lastLoadError = lastLoadError,
            events = events.toList()
        )
    }

    private fun videoFrameProcessingOffsetAverageUs(): Long? =
        if (videoFrameProcessingOffsetCount > 0) {
            videoFrameProcessingOffsetTotalUs / videoFrameProcessingOffsetCount
        } else {
            null
        }

    private fun record(
        name: String,
        eventTime: AnalyticsListener.EventTime? = null,
        details: Map<String, String> = emptyMap()
    ) {
        val now = SystemClock.elapsedRealtime()
        val timeMs = System.currentTimeMillis()
        eventCount += 1
        val position = eventTime?.currentPlaybackPositionMs.safeTimeMs() ?: positionMs
        val bufferedPosition = eventTime?.bufferedPositionMs() ?: bufferedPositionMs
        events.addLast(
            PlaybackIssuePlaybackEventInput(
                timeMs = timeMs,
                elapsedMs = (now - sessionStartedAtElapsedMs).coerceAtLeast(0L),
                name = name,
                playbackState = playbackStateName,
                positionMs = position,
                bufferedPositionMs = bufferedPosition,
                details = details
            )
        )
        while (events.size > PLAYBACK_ANALYTICS_EVENT_LIMIT) {
            events.removeFirst()
        }
    }
}

private fun LoadEventInfo.toPlaybackLoad(mediaLoadData: MediaLoadData): PlaybackIssuePlaybackLoadInput {
    val requestUri = uri
    return PlaybackIssuePlaybackLoadInput(
        host = requestUri.host,
        scheme = requestUri.scheme,
        dataType = mediaLoadData.dataType.dataTypeName(),
        trackType = mediaLoadData.trackType.trackTypeName(),
        httpMethod = dataSpec.getHttpMethodString(),
        position = dataSpec.position.takeIf { it >= 0L },
        length = dataSpec.length.takeIf { it >= 0L },
        durationMs = loadDurationMs.takeIf { it >= 0L },
        bytesLoaded = bytesLoaded.takeIf { it >= 0L },
        responseHeaderNames = responseHeaders.keys
            .mapNotNull { it.trim().takeIf(String::isNotBlank)?.lowercase() }
            .distinct()
            .sorted()
            .take(40)
    )
}

private fun Format.toPlaybackFormat(
    trackType: String?,
    support: String?,
    reuseEvaluation: DecoderReuseEvaluation?
): PlaybackIssuePlaybackFormatInput =
    PlaybackIssuePlaybackFormatInput(
        trackType = trackType,
        sampleMimeType = sampleMimeType,
        containerMimeType = containerMimeType,
        codecs = codecs,
        id = id,
        label = label,
        language = language,
        width = width.takeIf { it > 0 },
        height = height.takeIf { it > 0 },
        frameRate = frameRate.takeIf { it > 0f },
        bitrate = bitrate.takeIf { it > 0 },
        channelCount = channelCount.takeIf { it > 0 },
        sampleRate = sampleRate.takeIf { it > 0 },
        colorTransfer = colorInfo?.colorTransfer?.takeIf { it != C.COLOR_TRANSFER_SDR },
        selectionFlags = selectionFlags.takeIf { it != 0 },
        roleFlags = roleFlags.takeIf { it != 0 },
        support = support,
        decoderReuseResult = reuseEvaluation?.result?.decoderReuseResultName(),
        decoderDiscardReasons = reuseEvaluation?.discardReasons?.takeIf { it != 0 }
    )

private fun Format.formatDetails(trackType: String?): Map<String, String> =
    details(
        "trackType" to trackType,
        "mime" to sampleMimeType,
        "container" to containerMimeType,
        "codecs" to codecs,
        "id" to id,
        "label" to label,
        "language" to language,
        "size" to if (width > 0 && height > 0) "${width}x$height" else null,
        "frameRate" to frameRate.takeIf { it > 0f }?.toString(),
        "bitrate" to bitrate.takeIf { it > 0 }?.toString(),
        "channels" to channelCount.takeIf { it > 0 }?.toString(),
        "sampleRate" to sampleRate.takeIf { it > 0 }?.toString()
    )

private fun loadDetails(load: PlaybackIssuePlaybackLoadInput?): Map<String, String> =
    details(
        "host" to load?.host,
        "dataType" to load?.dataType,
        "trackType" to load?.trackType,
        "method" to load?.httpMethod,
        "position" to load?.position?.toString(),
        "length" to load?.length?.toString(),
        "bytesLoaded" to load?.bytesLoaded?.toString(),
        "durationMs" to load?.durationMs?.toString()
    )

private fun Throwable.findHttpStatus(): Int? {
    var current: Throwable? = this
    while (current != null) {
        if (current is HttpDataSource.InvalidResponseCodeException) {
            return current.responseCode
        }
        current = current.cause
    }
    return null
}

private fun Long?.safeTimeMs(): Long? =
    this?.takeIf { it != C.TIME_UNSET && it >= 0L }

private fun AnalyticsListener.EventTime.bufferedPositionMs(): Long? {
    val position = currentPlaybackPositionMs.safeTimeMs() ?: return null
    return (position + totalBufferedDurationMs.coerceAtLeast(0L)).coerceAtLeast(position)
}

private fun Int.playbackStateName(): String =
    when (this) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "UNKNOWN"
    }

private fun Int.trackTypeName(): String =
    when (this) {
        C.TRACK_TYPE_AUDIO -> "audio"
        C.TRACK_TYPE_VIDEO -> "video"
        C.TRACK_TYPE_TEXT -> "text"
        C.TRACK_TYPE_IMAGE -> "image"
        C.TRACK_TYPE_METADATA -> "metadata"
        C.TRACK_TYPE_CAMERA_MOTION -> "camera_motion"
        C.TRACK_TYPE_NONE -> "none"
        C.TRACK_TYPE_DEFAULT -> "default"
        C.TRACK_TYPE_UNKNOWN -> "unknown"
        else -> "custom_$this"
    }

private fun Int.dataTypeName(): String =
    when (this) {
        C.DATA_TYPE_MEDIA -> "media"
        C.DATA_TYPE_MEDIA_INITIALIZATION -> "media_initialization"
        C.DATA_TYPE_DRM -> "drm"
        C.DATA_TYPE_MANIFEST -> "manifest"
        C.DATA_TYPE_TIME_SYNCHRONIZATION -> "time_synchronization"
        C.DATA_TYPE_AD -> "ad"
        C.DATA_TYPE_MEDIA_PROGRESSIVE_LIVE -> "media_progressive_live"
        C.DATA_TYPE_UNKNOWN -> "unknown"
        else -> "custom_$this"
    }

private fun Int.decoderReuseResultName(): String =
    when (this) {
        DecoderReuseEvaluation.REUSE_RESULT_NO -> "no"
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_FLUSH -> "yes_with_flush"
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITH_RECONFIGURATION -> "yes_with_reconfiguration"
        DecoderReuseEvaluation.REUSE_RESULT_YES_WITHOUT_RECONFIGURATION -> "yes_without_reconfiguration"
        else -> "unknown_$this"
    }

private fun details(vararg pairs: Pair<String, String?>): Map<String, String> =
    pairs.mapNotNull { (key, value) ->
        value?.takeIf { it.isNotBlank() }?.let { key to it }
    }.toMap()
