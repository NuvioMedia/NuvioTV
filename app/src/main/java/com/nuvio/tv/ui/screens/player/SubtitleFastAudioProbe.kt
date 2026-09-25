package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioCapabilities
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.extractor.DefaultExtractorsFactory
import com.nuvio.tv.core.player.DolbyVisionConversionConfig
import com.nuvio.tv.core.player.DolbyVisionExtractorsFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.coroutineContext

private const val DEFAULT_FAST_AUDIO_PROBE_MAX_WALL_CLOCK_MS = 20_000L
private const val DEFAULT_FAST_AUDIO_PROBE_STARTUP_TIMEOUT_MS = 20_000L

internal data class SubtitleFastAudioProbeRequest(
    val streamUrl: String,
    val headers: Map<String, String>,
    val preferredStartMs: Long,
    val mediaDurationMs: Long,
    val selectedAudioTrack: TrackInfo?,
    val playbackSpeed: Float = 8f,
    /** Time allowed after the first PCM frame, excluding stream/index startup. */
    val maxWallClockMs: Long = DEFAULT_FAST_AUDIO_PROBE_MAX_WALL_CLOCK_MS,
    val startupTimeoutMs: Long = DEFAULT_FAST_AUDIO_PROBE_STARTUP_TIMEOUT_MS
)

internal enum class SubtitleFastAudioProbeTermination {
    TARGET_REACHED,
    EOF,
    WALL_TIMEOUT,
    ERROR
}

internal data class SubtitleFastAudioProbeResult(
    val snapshot: SubtitleSpeechSnapshot?,
    val decodedStartMs: Long?,
    val decodedEndMs: Long?,
    val failureReason: String? = null,
    val termination: SubtitleFastAudioProbeTermination = SubtitleFastAudioProbeTermination.ERROR
) {
    val decodedDurationMs: Long
        get() = if (decodedStartMs != null && decodedEndMs != null) {
            (decodedEndMs - decodedStartMs).coerceAtLeast(0L)
        } else {
            0L
        }

    val observedDurationMs: Long
        get() = snapshot?.let { mergedObservedDurationMs(it.observedSpans) } ?: 0L
}

/**
 * On-demand, audio-only ExoPlayer used by Auto Sync.
 *
 * The player is created on the first [probe], reused for later seeks in the same Auto Sync run,
 * and released explicitly after discovery/validation. It uses its own MediaSource/LoadControl,
 * never attaches a video surface, and has video/text/image/metadata tracks disabled. Its data
 * source deliberately bypasses [PlayerMediaSourceFactory]'s shared VOD cache/session state while
 * retaining the same Nuvio HTTP stack and request headers.
 *
 * Audio is muted and audio-focus handling is disabled. Playback uses the request's adaptive speed;
 * the collector receives the decoder's original PCM (before speed processing) through
 * [PlaybackSpeedAwareAudioSink], so VAD timestamps remain on the media timeline. Player and sink
 * resources are always released from the application looper, including cancellation.
 */
internal class SubtitleFastAudioProbe(
    context: Context
) {
    private val appContext = context.applicationContext
    private var session: ProbeSession? = null

    private companion object {
        const val TAG = "SubtitleFastProbe"
        const val TARGET_AUDIO_MS = 60_000L
        const val PRE_ROLL_MS = 5_000L
        const val POLL_INTERVAL_MS = 40L
        const val RELEASE_TIMEOUT_MS = 2_000L

        const val MIN_BUFFER_MS = 2_000
        const val MAX_BUFFER_MS = 15_000
        const val BUFFER_FOR_PLAYBACK_MS = 250
        const val BUFFER_AFTER_REBUFFER_MS = 500
        const val TARGET_BUFFER_BYTES = 12 * 1024 * 1024
    }

    private data class ProbeSession(
        val key: String,
        val player: ExoPlayer,
        val collector: SubtitleSpeechProfileCollector,
        var activeRequest: SubtitleFastAudioProbeRequest? = null,
        var requestedStartMs: Long = 0L,
        var playerError: PlaybackException? = null,
        var playbackEnded: Boolean = false,
        var audioOverrideResolved: Boolean = false,
        var trackSelectionFailure: String? = null
    )

    suspend fun probe(request: SubtitleFastAudioProbeRequest): SubtitleFastAudioProbeResult =
        withContext(Dispatchers.Main.immediate) {
            if (request.streamUrl.isBlank()) {
                return@withContext errorResult("Missing stream URL")
            }

            val requestedStartMs = resolveWindowStartMs(request)
            val startedAtMs = SystemClock.elapsedRealtime()
            var firstPcmAtMs: Long? = null
            var activeSession: ProbeSession? = null

            try {
                val playbackSpeed = request.playbackSpeed
                    .takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(1f, 8f)
                    ?: 1f
                val currentSession = ensureSession(request, playbackSpeed)
                activeSession = currentSession
                val collector = currentSession.collector
                currentSession.activeRequest = request
                currentSession.requestedStartMs = requestedStartMs
                currentSession.playerError = null
                currentSession.playbackEnded = false
                currentSession.audioOverrideResolved = request.selectedAudioTrack == null
                currentSession.trackSelectionFailure = null
                collector.beginSession(
                    key = "exo-fast:${request.streamUrl.hashCode()}:$requestedStartMs",
                    timelineAnchorMs = requestedStartMs
                )
                collector.stopCollecting(clearExisting = true)

                val localPlayer = currentSession.player
                localPlayer.playbackParameters = PlaybackParameters(playbackSpeed, 1f)
                localPlayer.seekTo(requestedStartMs)
                if (request.selectedAudioTrack == null) {
                    collector.startCollecting(clearExisting = true)
                } else if (localPlayer.currentTracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }) {
                    resolveSelectedAudioTrack(currentSession, localPlayer.currentTracks)
                }
                if (localPlayer.playbackState == Player.STATE_IDLE) localPlayer.prepare()
                localPlayer.play()

                val playbackStartedAtMs = SystemClock.elapsedRealtime()
                val activeTimeoutMs = request.maxWallClockMs.coerceAtLeast(1L)
                val startupTimeoutMs = request.startupTimeoutMs.coerceAtLeast(1L)
                val termination = withTimeoutOrNull<SubtitleFastAudioProbeTermination>(
                    startupTimeoutMs + activeTimeoutMs + POLL_INTERVAL_MS * 2L
                ) {
                    while (true) {
                        coroutineContext.ensureActive()
                        currentSession.playerError?.let { throw it }
                        if (
                            !currentSession.audioOverrideResolved &&
                            currentSession.player.playbackState == Player.STATE_READY
                        ) {
                            resolveSelectedAudioTrack(
                                currentSession,
                                currentSession.player.currentTracks
                            )
                        }
                        if (currentSession.trackSelectionFailure != null) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.ERROR
                        }

                        val snapshot = collector.snapshot()
                        val nowMs = SystemClock.elapsedRealtime()
                        if (firstPcmAtMs == null && snapshot.observedDurationMs() > 0L) {
                            firstPcmAtMs = nowMs
                        }
                        if (snapshot.failureReason != null) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.ERROR
                        }

                        // ExoPlayer timestamps can start beyond the requested seek position
                        // (notably with large MKV cue tables). An absolute end timestamp therefore
                        // cannot prove that a full window was decoded. Count the union of PCM that
                        // the collector actually observed instead.
                        if (hasReachedSubtitleFastAudioTarget(snapshot.observedSpans)) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.TARGET_REACHED
                        }
                        if (currentSession.playbackEnded) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.EOF
                        }
                        val pcmStartedAtMs = firstPcmAtMs
                        if (pcmStartedAtMs == null) {
                            if (nowMs - playbackStartedAtMs >= startupTimeoutMs) {
                                return@withTimeoutOrNull SubtitleFastAudioProbeTermination.WALL_TIMEOUT
                            }
                        } else if (nowMs - pcmStartedAtMs >= activeTimeoutMs) {
                            return@withTimeoutOrNull SubtitleFastAudioProbeTermination.WALL_TIMEOUT
                        }
                        delay(POLL_INTERVAL_MS)
                    }
                    @Suppress("UNREACHABLE_CODE")
                    SubtitleFastAudioProbeTermination.ERROR
                } ?: SubtitleFastAudioProbeTermination.WALL_TIMEOUT

                val snapshot = collector.snapshot()
                val failureReason = when {
                    currentSession.playerError != null -> currentSession.playerError?.message
                    currentSession.trackSelectionFailure != null ->
                        currentSession.trackSelectionFailure
                    snapshot.failureReason != null -> snapshot.failureReason
                    termination == SubtitleFastAudioProbeTermination.WALL_TIMEOUT ->
                        if (firstPcmAtMs == null) {
                            "Audio probe timed out before receiving PCM after $startupTimeoutMs ms"
                        } else {
                            "Audio probe timed out after $activeTimeoutMs ms of active decoding"
                        }
                    termination == SubtitleFastAudioProbeTermination.ERROR ->
                        "PCM speech analysis failed"
                    else -> null
                }
                Log.i(
                    TAG,
                    "Exo audio probe finished: termination=$termination speed=${playbackSpeed}x " +
                        "wall=${SystemClock.elapsedRealtime() - startedAtMs}ms " +
                        "firstPcm=${firstPcmAtMs?.minus(playbackStartedAtMs) ?: -1L}ms " +
                        "decoded=${snapshot.observedDurationMs()}ms"
                )
                snapshot.toProbeResult(termination, failureReason)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                Log.w(TAG, "Exo audio probe unavailable: ${error.message}", error)
                activeSession?.collector?.snapshot().orEmptyProbeSnapshot().toProbeResult(
                    termination = SubtitleFastAudioProbeTermination.ERROR,
                    failureReason = error.message ?: error.javaClass.simpleName
                )
            } finally {
                activeSession?.let { current ->
                    current.collector.stopCollecting(clearExisting = false)
                    current.activeRequest = null
                    runCatching { current.player.pause() }
                    if (current.playerError != null) releaseSession()
                }
            }
        }

    /** Releases the one on-demand player after discovery and validation have both completed. */
    suspend fun release() = withContext(NonCancellable + Dispatchers.Main.immediate) {
        releaseSession()
    }

    private fun ensureSession(
        request: SubtitleFastAudioProbeRequest,
        playbackSpeed: Float
    ): ProbeSession {
        val key = buildString {
            append(request.streamUrl)
            append('|').append(request.headers.toSortedMap().hashCode())
            append('|').append(request.selectedAudioTrack?.trackId)
            append('|').append(request.selectedAudioTrack?.index)
        }
        session?.takeIf { it.key == key }?.let { return it }
        releaseSession()

        val normalizedRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(
            request.streamUrl,
            request.headers
        )
        val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(
            appContext,
            normalizedRequest.headers
        )
        val extractorsFactory = DolbyVisionExtractorsFactory(
            delegate = DefaultExtractorsFactory(),
            config = DolbyVisionConversionConfig(active = false)
        )
        val mediaSourceFactory = DefaultMediaSourceFactory(dataSourceFactory, extractorsFactory)
        val trackSelector = DefaultTrackSelector(appContext).apply {
            val parametersBuilder = buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_IMAGE, true)
                .setTrackTypeDisabled(C.TRACK_TYPE_METADATA, true)
            request.selectedAudioTrack?.language
                ?.takeIf { it.isNotBlank() && !it.equals("und", ignoreCase = true) }
                ?.let(parametersBuilder::setPreferredAudioLanguage)
            setParameters(parametersBuilder)
        }
        val collector = SubtitleSpeechProfileCollector(timelineAnchorMs = null)
        val renderersFactory = SubtitleProbeRenderersFactory(
            context = appContext,
            collector = collector,
            playbackSpeed = playbackSpeed
        )
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_ON)
            .setEnableDecoderFallback(true)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                MIN_BUFFER_MS,
                MAX_BUFFER_MS,
                BUFFER_FOR_PLAYBACK_MS,
                BUFFER_AFTER_REBUFFER_MS
            )
            .setTargetBufferBytes(TARGET_BUFFER_BYTES)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(0, false)
            .build()
        val player = ExoPlayer.Builder(appContext, renderersFactory)
            .setLooper(Looper.getMainLooper())
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(mediaSourceFactory)
            .setLoadControl(loadControl)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                /* handleAudioFocus = */ false
            )
            .setHandleAudioBecomingNoisy(false)
            .setReleaseTimeoutMs(RELEASE_TIMEOUT_MS)
            .build()
        val created = ProbeSession(key = key, player = player, collector = collector)
        player.addListener(object : Player.Listener {
            override fun onTracksChanged(tracks: Tracks) {
                resolveSelectedAudioTrack(created, tracks)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                created.playbackEnded = playbackState == Player.STATE_ENDED
            }

            override fun onPlayerError(error: PlaybackException) {
                created.playerError = error
            }
        })
        val mediaItemBuilder = MediaItem.Builder().setUri(normalizedRequest.url)
        PlayerMediaSourceFactory.inferMimeType(
            url = normalizedRequest.url,
            filename = null
        )?.let(mediaItemBuilder::setMimeType)
        player.volume = 0f
        player.setMediaItem(mediaItemBuilder.build())
        session = created
        return created
    }

    private fun resolveSelectedAudioTrack(current: ProbeSession, tracks: Tracks) {
        val request = current.activeRequest ?: return
        val selected = request.selectedAudioTrack
        if (selected == null || current.audioOverrideResolved) return
        val target = findBestAudioTrack(tracks, selected)
        if (target == null) {
            if (
                current.player.playbackState == Player.STATE_READY &&
                tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO }
            ) {
                current.trackSelectionFailure =
                    "Could not confidently match the selected audio track"
            }
            return
        }
        current.trackSelectionFailure = null
        if (!target.group.isTrackSelected(target.trackIndex)) {
            current.collector.resetForAudioTrackChange()
            current.player.trackSelectionParameters =
                current.player.trackSelectionParameters
                    .buildUpon()
                    .setOverrideForType(
                        TrackSelectionOverride(target.group.mediaTrackGroup, target.trackIndex)
                    )
                    .build()
            current.player.seekTo(current.requestedStartMs)
            return
        }

        current.audioOverrideResolved = true
        // Track discovery may already have decoded default-track PCM while collection was paused.
        // Re-seek before enabling the collector so the requested window is not silently shortened.
        current.player.seekTo(current.requestedStartMs)
        current.collector.startCollecting(clearExisting = true)
        Log.i(
            TAG,
            "Matched audio track ordinal=${target.audioOrdinal} id=${target.format.id} " +
                "language=${target.format.language} channels=${target.format.channelCount} " +
                "score=${audioTrackMatchScore(target, selected)}"
        )
    }

    private fun releaseSession() {
        val current = session ?: return
        session = null
        current.collector.stopCollecting(clearExisting = true)
        runCatching { current.player.stop() }
        runCatching { current.player.release() }
    }

    private fun resolveWindowStartMs(request: SubtitleFastAudioProbeRequest): Long {
        val preferred = (request.preferredStartMs - PRE_ROLL_MS).coerceAtLeast(0L)
        if (request.mediaDurationMs <= 0L) return preferred
        return preferred.coerceAtMost((request.mediaDurationMs - TARGET_AUDIO_MS).coerceAtLeast(0L))
    }

    private fun errorResult(reason: String) = SubtitleFastAudioProbeResult(
        snapshot = null,
        decodedStartMs = null,
        decodedEndMs = null,
        failureReason = reason,
        termination = SubtitleFastAudioProbeTermination.ERROR
    )
}

private fun SubtitleSpeechSnapshot?.orEmptyProbeSnapshot(): SubtitleSpeechSnapshot = this
    ?: SubtitleSpeechSnapshot(
        speechSpans = emptyList(),
        observedSpans = emptyList(),
        pcmAvailable = false
    )

/** PCM-only sink factory for the reusable probe player. */
private class SubtitleProbeRenderersFactory(
    context: Context,
    private val collector: SubtitleSpeechProfileCollector,
    private val playbackSpeed: Float
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): AudioSink {
        // DEFAULT_AUDIO_CAPABILITIES deliberately excludes encoded passthrough. This guarantees
        // that the forwarding sink sees PCM even when the TV advertises AC3/DTS/TrueHD support.
        val pcmSink = DefaultAudioSink.Builder()
            .setAudioCapabilities(AudioCapabilities.DEFAULT_AUDIO_CAPABILITIES)
            .setEnableFloatOutput(false)
            .setEnableAudioTrackPlaybackParams(false)
            .build()
        return PlaybackSpeedAwareAudioSink(
            sink = pcmSink,
            initialForcePcm = true,
            forcePcmForBluetooth = false,
            subtitleSpeechProfileCollector = collector
        ).apply {
            setInitialPlaybackSpeed(playbackSpeed)
        }
    }
}

private data class ProbeAudioTrack(
    val group: Tracks.Group,
    val trackIndex: Int,
    val audioOrdinal: Int,
    val format: Format
)

private fun findBestAudioTrack(tracks: Tracks, selected: TrackInfo): ProbeAudioTrack? {
    val candidates = buildList {
        var audioOrdinal = 0
        tracks.groups.forEach { group ->
            if (group.type != C.TRACK_TYPE_AUDIO) return@forEach
            for (trackIndex in 0 until group.length) {
                add(
                    ProbeAudioTrack(
                        group = group,
                        trackIndex = trackIndex,
                        audioOrdinal = audioOrdinal++,
                        format = group.getTrackFormat(trackIndex)
                    )
                )
            }
        }
    }
    if (candidates.size == 1) return candidates.first()
    val ranked = candidates
        .map { candidate -> candidate to audioTrackMatchScore(candidate, selected) }
        .sortedByDescending { it.second }
    val best = ranked.firstOrNull() ?: return null
    val runnerUpScore = ranked.getOrNull(1)?.second ?: Int.MIN_VALUE
    val hasStrongIdentity = best.second >= 90
    val hasUsefulIdentity = best.second >= 40 && best.second - runnerUpScore >= 10
    return best.first.takeIf { hasStrongIdentity || hasUsefulIdentity }
}

private fun audioTrackMatchScore(candidate: ProbeAudioTrack, selected: TrackInfo): Int {
    val format = candidate.format
    var score = 0
    if (!selected.trackId.isNullOrBlank() && format.id == selected.trackId) score += 100
    if (candidate.audioOrdinal == selected.index) score += 50
    if (!selected.language.isNullOrBlank() &&
        PlayerSubtitleUtils.matchesLanguageCode(format.language, selected.language)
    ) {
        score += 40
    }
    if (selected.channelCount != null && format.channelCount == selected.channelCount) score += 12
    if (selected.sampleRate != null && format.sampleRate == selected.sampleRate) score += 6
    val codecHint = selected.codec?.lowercase().orEmpty()
    if (codecHint.isNotBlank()) {
        val formatHints = listOfNotNull(format.sampleMimeType, format.codecs)
        if (formatHints.any { hint ->
                val normalizedHint = hint.substringAfter('/').replace('-', ' ')
                codecHint.contains(normalizedHint, ignoreCase = true) ||
                    normalizedHint.contains(codecHint, ignoreCase = true)
            }
        ) {
            score += 4
        }
    }
    return score
}

private fun SubtitleSpeechSnapshot.toProbeResult(
    termination: SubtitleFastAudioProbeTermination,
    failureReason: String?
): SubtitleFastAudioProbeResult = SubtitleFastAudioProbeResult(
    snapshot = takeIf { it.pcmAvailable },
    decodedStartMs = observedSpans.minOfOrNull { it.startMs },
    decodedEndMs = observedSpans.maxOfOrNull { it.endMs },
    failureReason = failureReason,
    termination = termination
)

private fun SubtitleSpeechSnapshot.observedDurationMs(): Long =
    mergedObservedDurationMs(observedSpans)

internal fun hasReachedSubtitleFastAudioTarget(
    observedSpans: List<SubtitleSyncSpan>,
    targetDurationMs: Long = 60_000L
): Boolean = mergedObservedDurationMs(observedSpans) >= targetDurationMs.coerceAtLeast(1L)

private fun mergedObservedDurationMs(observedSpans: List<SubtitleSyncSpan>): Long =
    SubtitleAutoSyncEngine.mergeSpans(observedSpans, allowedGapMs = 120L)
        .sumOf { span -> (span.endMs - span.startMs).coerceAtLeast(0L) }
