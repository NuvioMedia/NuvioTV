package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PlaybackIssueReportRequestDto(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "createdAtMs") val createdAtMs: Long,
    @Json(name = "app") val app: PlaybackIssueAppDto,
    @Json(name = "device") val device: PlaybackIssueDeviceDto,
    @Json(name = "content") val content: PlaybackIssueContentDto,
    @Json(name = "stream") val stream: PlaybackIssueStreamDto,
    @Json(name = "player") val player: PlaybackIssuePlayerDto,
    @Json(name = "loading") val loading: PlaybackIssueLoadingDto,
    @Json(name = "error") val error: PlaybackIssueErrorDto,
    @Json(name = "diagnostics") val diagnostics: PlaybackIssueDiagnosticsDto,
    @Json(name = "playbackSettings") val playbackSettings: PlaybackIssuePlaybackSettingsDto? = null,
    @Json(name = "playbackAnalytics") val playbackAnalytics: PlaybackIssuePlaybackAnalyticsDto? = null
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueAppDto(
    @Json(name = "applicationId") val applicationId: String,
    @Json(name = "versionName") val versionName: String,
    @Json(name = "versionCode") val versionCode: Long,
    @Json(name = "debugBuild") val debugBuild: Boolean
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueDeviceDto(
    @Json(name = "manufacturer") val manufacturer: String,
    @Json(name = "brand") val brand: String,
    @Json(name = "model") val model: String,
    @Json(name = "product") val product: String,
    @Json(name = "androidRelease") val androidRelease: String,
    @Json(name = "sdkInt") val sdkInt: Int,
    @Json(name = "supportedAbis") val supportedAbis: List<String>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueContentDto(
    @Json(name = "title") val title: String?,
    @Json(name = "contentName") val contentName: String?,
    @Json(name = "contentId") val contentId: String?,
    @Json(name = "contentType") val contentType: String?,
    @Json(name = "videoId") val videoId: String?,
    @Json(name = "season") val season: Int?,
    @Json(name = "episode") val episode: Int?,
    @Json(name = "episodeTitle") val episodeTitle: String?,
    @Json(name = "releaseYear") val releaseYear: String?
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueStreamDto(
    @Json(name = "host") val host: String?,
    @Json(name = "scheme") val scheme: String?,
    @Json(name = "port") val port: Int?,
    @Json(name = "urlHash") val urlHash: String?,
    @Json(name = "urlWithoutQueryHash") val urlWithoutQueryHash: String?,
    @Json(name = "fileExtension") val fileExtension: String?,
    @Json(name = "mimeType") val mimeType: String?,
    @Json(name = "streamName") val streamName: String?,
    @Json(name = "addonName") val addonName: String?,
    @Json(name = "videoHash") val videoHash: String?,
    @Json(name = "videoSize") val videoSize: Long?,
    @Json(name = "requestHeaderNames") val requestHeaderNames: List<String>,
    @Json(name = "responseHeaderNames") val responseHeaderNames: List<String>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlayerDto(
    @Json(name = "engine") val engine: String,
    @Json(name = "positionMs") val positionMs: Long?,
    @Json(name = "durationMs") val durationMs: Long?,
    @Json(name = "bufferedPositionMs") val bufferedPositionMs: Long?,
    @Json(name = "audioTrack") val audioTrack: String?,
    @Json(name = "subtitleTrack") val subtitleTrack: String?,
    @Json(name = "isTorrentStream") val isTorrentStream: Boolean
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueErrorDto(
    @Json(name = "displayMessage") val displayMessage: String?,
    @Json(name = "errorCode") val errorCode: Int?,
    @Json(name = "errorCodeName") val errorCodeName: String?,
    @Json(name = "exceptionClass") val exceptionClass: String?,
    @Json(name = "causeClass") val causeClass: String?,
    @Json(name = "causeMessage") val causeMessage: String?,
    @Json(name = "httpStatus") val httpStatus: Int?
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueLoadingDto(
    @Json(name = "phase") val phase: String,
    @Json(name = "message") val message: String?,
    @Json(name = "progress") val progress: Float?,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "phaseElapsedMs") val phaseElapsedMs: Long,
    @Json(name = "reportReason") val reportReason: String,
    @Json(name = "loadingOverlayVisible") val loadingOverlayVisible: Boolean,
    @Json(name = "loadingStatusVisible") val loadingStatusVisible: Boolean,
    @Json(name = "hasRenderedFirstFrame") val hasRenderedFirstFrame: Boolean,
    @Json(name = "exoPlayerCreated") val exoPlayerCreated: Boolean,
    @Json(name = "exoPlaybackState") val exoPlaybackState: Int?,
    @Json(name = "exoPlaybackStateName") val exoPlaybackStateName: String?,
    @Json(name = "exoIsLoading") val exoIsLoading: Boolean?,
    @Json(name = "exoPlayWhenReady") val exoPlayWhenReady: Boolean?,
    @Json(name = "mpvAttached") val mpvAttached: Boolean,
    @Json(name = "startupRetryCount") val startupRetryCount: Int,
    @Json(name = "errorRetryCount") val errorRetryCount: Int,
    @Json(name = "timeoutRecoveryAttempts") val timeoutRecoveryAttempts: Int,
    @Json(name = "isLoadingAddonSubtitles") val isLoadingAddonSubtitles: Boolean,
    @Json(name = "addonSubtitlesCount") val addonSubtitlesCount: Int,
    @Json(name = "isLoadingSourceStreams") val isLoadingSourceStreams: Boolean,
    @Json(name = "isLoadingEpisodeStreams") val isLoadingEpisodeStreams: Boolean,
    @Json(name = "torrentDownloadSpeed") val torrentDownloadSpeed: Long,
    @Json(name = "torrentPeers") val torrentPeers: Int,
    @Json(name = "torrentSeeds") val torrentSeeds: Int,
    @Json(name = "events") val events: List<PlaybackIssueLoadingEventDto>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueLoadingEventDto(
    @Json(name = "timeMs") val timeMs: Long,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "phase") val phase: String,
    @Json(name = "message") val message: String?,
    @Json(name = "progress") val progress: Float?,
    @Json(name = "detail") val detail: String?
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueDiagnosticsDto(
    @Json(name = "timestampMs") val timestampMs: Long,
    @Json(name = "host") val host: String,
    @Json(name = "hdrCapsKnown") val hdrCapsKnown: Boolean,
    @Json(name = "displayDv") val displayDv: Boolean,
    @Json(name = "displayHdr10") val displayHdr10: Boolean,
    @Json(name = "displayHdr10Plus") val displayHdr10Plus: Boolean,
    @Json(name = "codecDv7Supported") val codecDv7Supported: Boolean,
    @Json(name = "dv81DecoderName") val dv81DecoderName: String?,
    @Json(name = "bridgeReady") val bridgeReady: Boolean,
    @Json(name = "bridgeVersion") val bridgeVersion: String?,
    @Json(name = "bridgeReason") val bridgeReason: String?,
    @Json(name = "dv7ModeRequested") val dv7ModeRequested: String,
    @Json(name = "dv7ModeEffective") val dv7ModeEffective: String,
    @Json(name = "dv7AutoDecision") val dv7AutoDecision: String?,
    @Json(name = "bufferEngineEnabled") val bufferEngineEnabled: Boolean,
    @Json(name = "parallelNetworkEnabled") val parallelNetworkEnabled: Boolean,
    @Json(name = "firstFrameMs") val firstFrameMs: Long,
    @Json(name = "dv7DoviCalls") val dv7DoviCalls: Int,
    @Json(name = "dv7DoviSuccess") val dv7DoviSuccess: Int,
    @Json(name = "dv7DoviSignalRewrites") val dv7DoviSignalRewrites: Int,
    @Json(name = "dvSourceProfile") val dvSourceProfile: String?,
    @Json(name = "videoResolution") val videoResolution: String?,
    @Json(name = "videoCodec") val videoCodec: String?,
    @Json(name = "videoHdrType") val videoHdrType: String?,
    @Json(name = "rebufferCount") val rebufferCount: Int,
    @Json(name = "rebufferTotalMs") val rebufferTotalMs: Long,
    @Json(name = "result") val result: String
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackSettingsDto(
    @Json(name = "playerPreference") val playerPreference: String,
    @Json(name = "internalPlayerEngine") val internalPlayerEngine: String,
    @Json(name = "resolvedInternalPlayerEngine") val resolvedInternalPlayerEngine: String,
    @Json(name = "autoSwitchInternalPlayerOnError") val autoSwitchInternalPlayerOnError: Boolean,
    @Json(name = "decoderPriority") val decoderPriority: Int,
    @Json(name = "decoderPriorityName") val decoderPriorityName: String,
    @Json(name = "effectiveDecoderPriority") val effectiveDecoderPriority: Int,
    @Json(name = "effectiveDecoderPriorityName") val effectiveDecoderPriorityName: String,
    @Json(name = "downmixEnabled") val downmixEnabled: Boolean,
    @Json(name = "audioOutputChannels") val audioOutputChannels: String,
    @Json(name = "maintainOriginalAudioOnDownmix") val maintainOriginalAudioOnDownmix: Boolean,
    @Json(name = "tunnelingEnabled") val tunnelingEnabled: Boolean,
    @Json(name = "tunnelingEffective") val tunnelingEffective: Boolean,
    @Json(name = "forceOpticalPassthrough") val forceOpticalPassthrough: Boolean,
    @Json(name = "skipSilence") val skipSilence: Boolean,
    @Json(name = "audioAmplificationDb") val audioAmplificationDb: Int,
    @Json(name = "centerMixLevelDb") val centerMixLevelDb: Int,
    @Json(name = "persistAudioAmplification") val persistAudioAmplification: Boolean,
    @Json(name = "rememberAudioDelayPerDevice") val rememberAudioDelayPerDevice: Boolean,
    @Json(name = "preferredAudioLanguage") val preferredAudioLanguage: String,
    @Json(name = "secondaryPreferredAudioLanguage") val secondaryPreferredAudioLanguage: String?,
    @Json(name = "preferredSubtitleLanguage") val preferredSubtitleLanguage: String,
    @Json(name = "secondaryPreferredSubtitleLanguage") val secondaryPreferredSubtitleLanguage: String?,
    @Json(name = "useForcedSubtitles") val useForcedSubtitles: Boolean,
    @Json(name = "showOnlyPreferredSubtitleLanguages") val showOnlyPreferredSubtitleLanguages: Boolean,
    @Json(name = "useLibass") val useLibass: Boolean,
    @Json(name = "activePlayerUsesLibass") val activePlayerUsesLibass: Boolean,
    @Json(name = "libassRenderType") val libassRenderType: String,
    @Json(name = "addonSubtitleStartupMode") val addonSubtitleStartupMode: String,
    @Json(name = "externalPlayerForwardSubtitles") val externalPlayerForwardSubtitles: Boolean,
    @Json(name = "subtitleOrganizationMode") val subtitleOrganizationMode: String,
    @Json(name = "loadingOverlayEnabled") val loadingOverlayEnabled: Boolean,
    @Json(name = "showPlayerLoadingStatus") val showPlayerLoadingStatus: Boolean,
    @Json(name = "playbackIssueReportsEnabled") val playbackIssueReportsEnabled: Boolean,
    @Json(name = "dv5ToDv81Enabled") val dv5ToDv81Enabled: Boolean,
    @Json(name = "dv7ToDv81PreserveMappingEnabled") val dv7ToDv81PreserveMappingEnabled: Boolean,
    @Json(name = "dv7HandlingMode") val dv7HandlingMode: String,
    @Json(name = "dv7LibdoviModeOverride") val dv7LibdoviModeOverride: Int,
    @Json(name = "stripHdr10PlusSei") val stripHdr10PlusSei: Boolean,
    @Json(name = "mpvHardwareDecodeMode") val mpvHardwareDecodeMode: String,
    @Json(name = "frameRateMatchingMode") val frameRateMatchingMode: String,
    @Json(name = "resolutionMatchingEnabled") val resolutionMatchingEnabled: Boolean,
    @Json(name = "resizeMode") val resizeMode: Int,
    @Json(name = "aspectMode") val aspectMode: String,
    @Json(name = "bufferEngineEnabled") val bufferEngineEnabled: Boolean,
    @Json(name = "minBufferMs") val minBufferMs: Int,
    @Json(name = "maxBufferMs") val maxBufferMs: Int,
    @Json(name = "bufferForPlaybackMs") val bufferForPlaybackMs: Int,
    @Json(name = "bufferForPlaybackAfterRebufferMs") val bufferForPlaybackAfterRebufferMs: Int,
    @Json(name = "targetBufferSizeMb") val targetBufferSizeMb: Int,
    @Json(name = "backBufferDurationMs") val backBufferDurationMs: Int,
    @Json(name = "effectiveBackBufferDurationMs") val effectiveBackBufferDurationMs: Int,
    @Json(name = "retainBackBufferFromKeyframe") val retainBackBufferFromKeyframe: Boolean,
    @Json(name = "parallelNetworkEnabled") val parallelNetworkEnabled: Boolean,
    @Json(name = "bufferBudgetManaged") val bufferBudgetManaged: Boolean,
    @Json(name = "allowLargeTargetBuffer") val allowLargeTargetBuffer: Boolean,
    @Json(name = "vodCacheEnabled") val vodCacheEnabled: Boolean,
    @Json(name = "vodCacheSizeMode") val vodCacheSizeMode: String,
    @Json(name = "vodCacheSizeMb") val vodCacheSizeMb: Int,
    @Json(name = "useParallelConnections") val useParallelConnections: Boolean,
    @Json(name = "parallelConnectionCount") val parallelConnectionCount: Int,
    @Json(name = "parallelChunkSizeMb") val parallelChunkSizeMb: Int,
    @Json(name = "enableHttp2") val enableHttp2: Boolean,
    @Json(name = "nuvioPerformanceModeEnabled") val nuvioPerformanceModeEnabled: Boolean,
    @Json(name = "streamAutoPlayMode") val streamAutoPlayMode: String,
    @Json(name = "streamAutoPlaySource") val streamAutoPlaySource: String,
    @Json(name = "streamAutoPlayNextEpisodeEnabled") val streamAutoPlayNextEpisodeEnabled: Boolean,
    @Json(name = "streamAutoPlayPreferBingeGroupForNextEpisode") val streamAutoPlayPreferBingeGroupForNextEpisode: Boolean,
    @Json(name = "streamAutoPlayReuseBingeGroup") val streamAutoPlayReuseBingeGroup: Boolean,
    @Json(name = "streamAutoPlayTimeoutSeconds") val streamAutoPlayTimeoutSeconds: Int,
    @Json(name = "stillWatchingEnabled") val stillWatchingEnabled: Boolean,
    @Json(name = "stillWatchingEpisodeThreshold") val stillWatchingEpisodeThreshold: Int,
    @Json(name = "nextEpisodeThresholdMode") val nextEpisodeThresholdMode: String,
    @Json(name = "nextEpisodeThresholdPercent") val nextEpisodeThresholdPercent: Float,
    @Json(name = "nextEpisodeThresholdMinutesBeforeEnd") val nextEpisodeThresholdMinutesBeforeEnd: Float,
    @Json(name = "streamReuseLastLinkEnabled") val streamReuseLastLinkEnabled: Boolean,
    @Json(name = "streamReuseLastLinkCacheHours") val streamReuseLastLinkCacheHours: Int
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackAnalyticsDto(
    @Json(name = "schemaVersion") val schemaVersion: Int,
    @Json(name = "sessionStartedAtMs") val sessionStartedAtMs: Long,
    @Json(name = "capturedAtMs") val capturedAtMs: Long,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "eventCount") val eventCount: Int,
    @Json(name = "lastEventElapsedMs") val lastEventElapsedMs: Long?,
    @Json(name = "playbackState") val playbackState: Int?,
    @Json(name = "playbackStateName") val playbackStateName: String?,
    @Json(name = "playWhenReady") val playWhenReady: Boolean?,
    @Json(name = "isPlaying") val isPlaying: Boolean?,
    @Json(name = "isLoading") val isLoading: Boolean?,
    @Json(name = "positionMs") val positionMs: Long?,
    @Json(name = "bufferedPositionMs") val bufferedPositionMs: Long?,
    @Json(name = "durationMs") val durationMs: Long?,
    @Json(name = "bufferedPercentage") val bufferedPercentage: Int?,
    @Json(name = "firstFrameElapsedMs") val firstFrameElapsedMs: Long?,
    @Json(name = "renderedFirstFrameCount") val renderedFirstFrameCount: Int,
    @Json(name = "rebufferCount") val rebufferCount: Int,
    @Json(name = "rebufferTotalMs") val rebufferTotalMs: Long,
    @Json(name = "currentRebufferMs") val currentRebufferMs: Long,
    @Json(name = "positionStallCount") val positionStallCount: Int,
    @Json(name = "longestPositionStallMs") val longestPositionStallMs: Long,
    @Json(name = "droppedFrames") val droppedFrames: Int,
    @Json(name = "maxDroppedFramesInEvent") val maxDroppedFramesInEvent: Int,
    @Json(name = "videoDecoderName") val videoDecoderName: String?,
    @Json(name = "videoDecoderInitMs") val videoDecoderInitMs: Long?,
    @Json(name = "videoDecoderReleaseCount") val videoDecoderReleaseCount: Int,
    @Json(name = "videoRenderedOutputBuffers") val videoRenderedOutputBuffers: Int?,
    @Json(name = "videoDroppedBuffers") val videoDroppedBuffers: Int?,
    @Json(name = "videoMaxConsecutiveDroppedBuffers") val videoMaxConsecutiveDroppedBuffers: Int?,
    @Json(name = "videoFrameProcessingOffsetAverageUs") val videoFrameProcessingOffsetAverageUs: Long?,
    @Json(name = "videoFormat") val videoFormat: PlaybackIssuePlaybackFormatDto?,
    @Json(name = "audioDecoderName") val audioDecoderName: String?,
    @Json(name = "audioDecoderInitMs") val audioDecoderInitMs: Long?,
    @Json(name = "audioDecoderReleaseCount") val audioDecoderReleaseCount: Int,
    @Json(name = "audioUnderrunCount") val audioUnderrunCount: Int,
    @Json(name = "audioUnderrunBufferSize") val audioUnderrunBufferSize: Int?,
    @Json(name = "audioUnderrunBufferSizeMs") val audioUnderrunBufferSizeMs: Long?,
    @Json(name = "audioUnderrunElapsedSinceLastFeedMs") val audioUnderrunElapsedSinceLastFeedMs: Long?,
    @Json(name = "audioFormat") val audioFormat: PlaybackIssuePlaybackFormatDto?,
    @Json(name = "bandwidthEstimateBps") val bandwidthEstimateBps: Long?,
    @Json(name = "bandwidthTransferDurationMs") val bandwidthTransferDurationMs: Int?,
    @Json(name = "bandwidthBytesTransferred") val bandwidthBytesTransferred: Long?,
    @Json(name = "loadStartedCount") val loadStartedCount: Int,
    @Json(name = "loadCompletedCount") val loadCompletedCount: Int,
    @Json(name = "loadCanceledCount") val loadCanceledCount: Int,
    @Json(name = "loadErrorCount") val loadErrorCount: Int,
    @Json(name = "totalBytesLoaded") val totalBytesLoaded: Long,
    @Json(name = "lastLoad") val lastLoad: PlaybackIssuePlaybackLoadDto?,
    @Json(name = "lastLoadError") val lastLoadError: PlaybackIssuePlaybackLoadErrorDto?,
    @Json(name = "events") val events: List<PlaybackIssuePlaybackEventDto>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackFormatDto(
    @Json(name = "trackType") val trackType: String?,
    @Json(name = "sampleMimeType") val sampleMimeType: String?,
    @Json(name = "containerMimeType") val containerMimeType: String?,
    @Json(name = "codecs") val codecs: String?,
    @Json(name = "id") val id: String?,
    @Json(name = "label") val label: String?,
    @Json(name = "language") val language: String?,
    @Json(name = "width") val width: Int?,
    @Json(name = "height") val height: Int?,
    @Json(name = "frameRate") val frameRate: Float?,
    @Json(name = "bitrate") val bitrate: Int?,
    @Json(name = "channelCount") val channelCount: Int?,
    @Json(name = "sampleRate") val sampleRate: Int?,
    @Json(name = "colorTransfer") val colorTransfer: Int?,
    @Json(name = "selectionFlags") val selectionFlags: Int?,
    @Json(name = "roleFlags") val roleFlags: Int?,
    @Json(name = "support") val support: String?,
    @Json(name = "decoderReuseResult") val decoderReuseResult: String?,
    @Json(name = "decoderDiscardReasons") val decoderDiscardReasons: Int?
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackLoadDto(
    @Json(name = "host") val host: String?,
    @Json(name = "scheme") val scheme: String?,
    @Json(name = "dataType") val dataType: String?,
    @Json(name = "trackType") val trackType: String?,
    @Json(name = "httpMethod") val httpMethod: String?,
    @Json(name = "position") val position: Long?,
    @Json(name = "length") val length: Long?,
    @Json(name = "durationMs") val durationMs: Long?,
    @Json(name = "bytesLoaded") val bytesLoaded: Long?,
    @Json(name = "responseHeaderNames") val responseHeaderNames: List<String>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackLoadErrorDto(
    @Json(name = "host") val host: String?,
    @Json(name = "dataType") val dataType: String?,
    @Json(name = "trackType") val trackType: String?,
    @Json(name = "exceptionClass") val exceptionClass: String?,
    @Json(name = "message") val message: String?,
    @Json(name = "httpStatus") val httpStatus: Int?,
    @Json(name = "wasCanceled") val wasCanceled: Boolean,
    @Json(name = "bytesLoaded") val bytesLoaded: Long?,
    @Json(name = "durationMs") val durationMs: Long?
)

@JsonClass(generateAdapter = true)
data class PlaybackIssuePlaybackEventDto(
    @Json(name = "timeMs") val timeMs: Long,
    @Json(name = "elapsedMs") val elapsedMs: Long,
    @Json(name = "name") val name: String,
    @Json(name = "playbackState") val playbackState: String?,
    @Json(name = "positionMs") val positionMs: Long?,
    @Json(name = "bufferedPositionMs") val bufferedPositionMs: Long?,
    @Json(name = "details") val details: Map<String, String>
)

@JsonClass(generateAdapter = true)
data class PlaybackIssueReportResponseDto(
    @Json(name = "reportId") val reportId: String? = null,
    @Json(name = "id") val id: String? = null
)
