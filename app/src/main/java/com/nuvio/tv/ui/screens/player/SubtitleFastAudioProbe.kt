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
}

/**
 * On-demand, audio-only ExoPlayer used by Auto Sync.
 *
 * The player is created only for [probe], uses its own MediaSource/LoadControl/track selector,
 * never attaches a video surface, and has video/text/image/metadata tracks disabled. Its data
 * source deliberately bypasses [PlayerMediaSourceFactory]'s shared VOD cache/session state while
 * retaining the same Nuvio HTTP stack and request headers.
 *
 * Audio is muted and audio-focus handling is disabled. Playback uses the request's adaptive speed;
 * the collector receives the decoder's original PCM (before speed processing) through
 * [PlaybackSpeedAwareAudioSink], so VAD timestamps remain on the media timeline. Every player and
 * sink resource is released from the application looper on success, timeout, error, or cancellation.
 */
internal class SubtitleFastAudioProbe(
    context: Context
) {
    private val appContext = context.applicationContext

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

    suspend fun probe(request: SubtitleFastAudioProbeRequest): SubtitleFastAudioProbeResult =
        withContext(Dispatchers.Main.immediate) {
            if (request.streamUrl.isBlank()) {
                return@withContext errorResult("Missing stream URL")
            }

            val requestedStartMs = resolveWindowStartMs(request)
            val collector = SubtitleSpeechProfileCollector(
                timelineAnchorMs = requestedStartMs
            ).apply {
                beginSession("exo-fast:${request.streamUrl.hashCode()}:$requestedStartMs")
                startCollecting(clearExisting = true)
            }

            var player: ExoPlayer? = null
            var playerError: PlaybackException? = null
            var playbackEnded = false
            var audioOverrideResolved = request.selectedAudioTrack == null
            val startedAtMs = SystemClock.elapsedRealtime()
            var firstPcmAtMs: Long? = null

            try {
                val normalizedRequest = PlayerMediaSourceFactory.normalizePlaybackRequest(
                    request.streamUrl,
                    request.headers
                )
                val dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(
                    appContext,
                    normalizedRequest.headers
                )
                // Keep the probe's network/session state independent, but use the same extractor
                // compatibility layer as normal playback. In particular, the vendored Matroska
                // extractor recognises DTS-HD tracks that stock Media3 may expose as core DTS.
                val extractorsFactory = DolbyVisionExtractorsFactory(
                    delegate = DefaultExtractorsFactory(),
                    config = DolbyVisionConversionConfig(active = false)
                )
                val mediaSourceFactory = DefaultMediaSourceFactory(
                    dataSourceFactory,
                    extractorsFactory
                )
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
                val playbackSpeed = request.playbackSpeed
                    .takeIf { it.isFinite() && it > 0f }
                    ?.coerceIn(1f, 8f)
                    ?: 1f
                val renderersFactory = SubtitleProbeRenderersFactory(
                    context = appContext,
                    collector = collector,
                    playbackSpeed = playbackSpeed
                )
                    .setExtensionRendererMode(
                        DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER
                    )
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

                val localPlayer = ExoPlayer.Builder(appContext, renderersFactory)
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
                player = localPlayer

                val listener = object : Player.Listener {
                    override fun onTracksChanged(tracks: Tracks) {
                        val selected = request.selectedAudioTrack
                        if (selected == null || audioOverrideResolved) return
                        val target = findBestAudioTrack(tracks, selected) ?: return
                        audioOverrideResolved = true

                        if (!target.group.isTrackSelected(target.trackIndex)) {
                            // Preparation may briefly select the default audio track before the
                            // complete manifest is known. Discard any such PCM and restart this
                            // probe window using the same audio track as the main player.
                            collector.resetForAudioTrackChange()
                            localPlayer.trackSelectionParameters =
                                localPlayer.trackSelectionParameters
                                    .buildUpon()
                                    .setOverrideForType(
                                        TrackSelectionOverride(
                                            target.group.mediaTrackGroup,
                                            target.trackIndex
                                        )
                                    )
                                    .build()
                            localPlayer.seekTo(requestedStartMs)
                        }
                    }

                    override fun onPlaybackStateChanged(playbackState: Int) {
                        playbackEnded = playbackState == Player.STATE_ENDED
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        playerError = error
                    }
                }
                localPlayer.addListener(listener)

                val mediaItemBuilder = MediaItem.Builder().setUri(normalizedRequest.url)
                PlayerMediaSourceFactory.inferMimeType(
                    url = normalizedRequest.url,
                    filename = null
                )?.let(mediaItemBuilder::setMimeType)

                localPlayer.volume = 0f
                localPlayer.playbackParameters = PlaybackParameters(playbackSpeed, 1f)
                localPlayer.setMediaItem(mediaItemBuilder.build())
                localPlayer.seekTo(requestedStartMs)
                localPlayer.prepare()
                localPlayer.play()

                val playbackStartedAtMs = SystemClock.elapsedRealtime()
                val activeTimeoutMs = request.maxWallClockMs.coerceAtLeast(1L)
                val startupTimeoutMs = request.startupTimeoutMs.coerceAtLeast(1L)
                val termination = withTimeoutOrNull<SubtitleFastAudioProbeTermination>(
                    startupTimeoutMs + activeTimeoutMs + POLL_INTERVAL_MS * 2L
                ) {
                    while (true) {
                        coroutineContext.ensureActive()
                        playerError?.let { throw it }

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
                        if (playbackEnded) {
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
                    playerError != null -> playerError?.message
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
                collector.snapshot().toProbeResult(
                    termination = SubtitleFastAudioProbeTermination.ERROR,
                    failureReason = error.message ?: error.javaClass.simpleName
                )
            } finally {
                collector.stopCollecting(clearExisting = false)
                // probe() is confined to Dispatchers.Main.immediate, which is also the player's
                // application looper. release() therefore cannot race an ExoPlayer callback.
                runCatching { player?.stop() }
                runCatching { player?.release() }
            }
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

/** PCM-only sink factory for the short-lived probe player. */
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
    return candidates.maxByOrNull { candidate ->
        audioTrackMatchScore(candidate, selected)
    }
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
