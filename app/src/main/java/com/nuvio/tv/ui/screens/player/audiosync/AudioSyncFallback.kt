@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.audiosync

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.CuesWithTiming
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.PlayerPlaybackNetworking
import com.nuvio.tv.ui.screens.player.PlayerRuntimeController
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import com.nuvio.tv.ui.screens.player.commitPreparedSidecarSubtitle
import com.nuvio.tv.ui.screens.player.currentSidecarGenerationFor
import com.nuvio.tv.ui.screens.player.parseSidecarTimedCuesRobust
import com.nuvio.tv.ui.screens.player.rememberAddonSubtitleSelection
import com.nuvio.tv.ui.screens.player.resetSubtitleDelayForAutoSync
import com.nuvio.tv.ui.screens.player.audiosync.asr.AsrModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Audio subtitle sync as AutoSync's fallback, for one stream of one TV player.
 *
 * AutoSync decides first. While it analyses a subtitle the audio is already being listened to
 * ([arm]); when it keeps the subtitle's original timing (no embedded subtitles, no usable
 * reference, or a weak match) the audio takes over ([takeOver]); otherwise nothing more is done
 * ([disarm]). The mapping found is applied by retiming the cues the player's sidecar shows, the
 * same way AutoSync applies its own result, so Nuvio's subtitle rendering is untouched. When the
 * sidecar moves on (another subtitle or track), the fallback stops on its own.
 */
internal class AudioSyncFallback private constructor(
    private val runtime: PlayerRuntimeController,
    private val player: ExoPlayer,
    private val sourceUrl: String,
) {
    private val appContext = runtime.context.applicationContext
    private val scope = runtime.scope
    private val controller = AudioSubtitleSyncController(
        context = appContext,
        // Subtitles are retimed in place, so the user's delay simply adds on top.
        manualDelayMs = { 0 },
        onStatus = ::onStatus,
        requestSwitch = ::replaceSubtitle,
    )

    /** The subtitle being synced, with its original cues and the sidecar attachment it lives in. */
    private class Target(val url: String, val cues: List<CuesWithTiming>, val generation: Long)

    private val target = AtomicReference<Target?>(null)
    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null
    private var settingsJob: Job? = null
    private var candidatesJob: Job? = null

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            controller.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        controller.listensBeforeSession = false
        controller.enabled = AudioSyncSettings.fallbackEnabled.value
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.onSourceChanged(sourceUrl)
        controller.setSpotSource(
            forSourceKey = sourceUrl,
            url = sourceUrl,
            dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(appContext, runtime.currentHeaders),
            extractorsFactory = DefaultExtractorsFactory(),
            localEngine = runtime._uiState.value.isTorrentStream || isLoopback(sourceUrl),
        )
        player.addListener(trackListener)
        controller.onAudioTrackSelected(player.currentTracks.selectedAudioFormat())
        AudioSyncTaps.attach(controller)
        // Turned off in Settings mid-playback: the subtitle goes back to AutoSync's (original) timing.
        settingsJob = scope.launch {
            AudioSyncSettings.fallbackEnabled.collect { enabled -> if (!enabled && target.get() != null) stop() }
        }
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm() {
        stop()
        if (!AudioSyncSettings.fallbackEnabled.value) return
        controller.enabled = true
        controller.samplingOnMobileData = AudioSyncSettings.samplingOnMobileData.value
        controller.listensBeforeSession = true
        if (!armed) {
            // Only now, so a stream AutoSync syncs on its own never loads the speech model or
            // fetches references for the fallback.
            armed = true
            val type = runtime.contentType
            val videoId = runtime.videoId
            if (!type.isNullOrBlank() && !videoId.isNullOrBlank()) controller.setContent(type, videoId)
        }
        // As on the phone, subtitles listed after AutoSync started join the search too.
        candidatesJob = scope.launch {
            runtime._uiState.map { it.addonSubtitles }.distinctUntilChanged().collect {
                controller.setReferenceSubtitles(referenceCandidates())
            }
        }
        startTicker()
    }

    /**
     * Every subtitle the add-ons returned for this title, not only the languages the menu shows:
     * English ones are the speech recognition's references, as on the phone.
     */
    private fun referenceCandidates(): List<AudioSubtitleSyncController.ReferenceCandidate> {
        val all = synchronized(allSubtitles) { allSubtitles[runtime] }
            ?.takeIf { (videoId, _) -> videoId == runtime.currentVideoId }
            ?.second
            .orEmpty()
        return (runtime._uiState.value.addonSubtitles + all).distinctBy { it.url }.map { subtitle ->
            AudioSubtitleSyncController.ReferenceCandidate(
                url = subtitle.url,
                language = subtitle.lang,
                headers = subtitle.headers.orEmpty(),
                label = subtitle.addonName,
            )
        }
    }

    /** AutoSync applied its own result: nothing is left for the audio to do. */
    fun disarm() {
        stop()
    }

    /**
     * AutoSync kept [url]'s original timing, shown by the sidecar: sync it to the audio instead.
     * Waits briefly for the sidecar's cues, as AutoSync does before applying its own result.
     * Returns false when the fallback is off, so AutoSync reports the failure itself.
     */
    fun takeOver(url: String): Boolean {
        if (!AudioSyncSettings.fallbackEnabled.value || !controller.enabled) {
            disarm()
            return false
        }
        takeOverJob?.cancel()
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (runtime.activeSidecarSubtitleKey == url && runtime.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = runtime.currentSidecarGenerationFor(url)
            val cues = runtime.sidecarTimedCues
            if (generation == null || cues.isEmpty()) return@launch disarm()
            target.set(Target(url, cues, generation))
            appliedModel = null
            startTicker()
            SyncLog.i("AutoSync kept the original timing of $url; syncing it to the audio")
            controller.startSession(url, cues)
        }
        return true
    }

    /** The user moved to another subtitle or track, or AutoSync starts over. */
    fun stop() {
        takeOverJob?.cancel()
        takeOverJob = null
        candidatesJob?.cancel()
        candidatesJob = null
        ticker?.cancel()
        ticker = null
        target.getAndSet(null)?.let { previous ->
            AutoSyncSyncedSubtitle.clear()
            // Leave the subtitle on its original timing if it is still the one shown.
            runtime.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = previous.url,
                newUrl = previous.url,
                cues = previous.cues,
                expectedGeneration = previous.generation,
            )
        }
        appliedModel = null
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    private fun release() {
        stop()
        settingsJob?.cancel()
        player.removeListener(trackListener)
        AudioSyncTaps.detach(controller)
        controller.release()
    }

    /** Feeds the playhead and applies each new mapping to the sidecar, on the main thread. */
    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                val current = target.get()
                // Another subtitle, a track or a stopped sidecar: the fallback steps aside.
                if (current != null && runtime.currentSidecarGenerationFor(current.url) != current.generation) {
                    stop()
                    return@launch
                }
                val durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L
                controller.onPlaybackPosition(player.currentPosition, durationMs)
                val model = controller.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        if (target.get() !== current) return
        val committed = runtime.commitPreparedSidecarSubtitle(
            expectedCurrentUrl = current.url,
            newUrl = current.url,
            cues = retimed,
            expectedGeneration = current.generation,
        )
        if (!committed) stop()
    }

    /** Automatic switch to another subtitle that fits the audio, as AutoSync replaces a subtitle. */
    private fun replaceSubtitle(url: String) {
        scope.launch {
            val current = target.get() ?: return@launch
            val subtitle = runtime._uiState.value.addonSubtitles.firstOrNull { it.url == url } ?: return@launch
            val cues = withContext(Dispatchers.IO) {
                runCatching {
                    val body = AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = subtitle.headers.orEmpty())
                    parseSidecarTimedCuesRobust(body, url).cues
                }.getOrDefault(emptyList())
            }
            if (cues.isEmpty() || target.get() !== current) return@launch
            val committed = runtime.commitPreparedSidecarSubtitle(
                expectedCurrentUrl = current.url,
                newUrl = url,
                cues = cues,
                expectedGeneration = current.generation,
            )
            val generation = runtime.currentSidecarGenerationFor(url)
            if (!committed || generation == null) return@launch
            AutoSyncSyncedSubtitle.clear()
            target.set(Target(url, cues, generation))
            appliedModel = null
            runtime._uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
            // Saved only over a subtitle the user chose, as AutoSync does.
            if (runtime.isUserExplicitSubtitleSelection) runtime.rememberAddonSubtitleSelection(subtitle)
            runtime.resetSubtitleDelayForAutoSync()
            // The new session adopts the mapping the switch was decided with.
            controller.startSession(url, cues)
        }
    }

    /** Keeps the subtitle list's "Auto synced" chip in step with the audio sync's result. */
    private fun onStatus(status: AudioSyncStatus) {
        when (status) {
            is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted ->
                target.get()?.let { AutoSyncSyncedSubtitle.mark(it.url) }
            AudioSyncStatus.Withdrawn -> AutoSyncSyncedSubtitle.clear()
            else -> Unit
        }
        toast(status)
    }

    private fun toast(status: AudioSyncStatus) {
        val message = status.message(appContext) ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(appContext, message, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L
        private val initialized = AtomicBoolean(false)
        private val fallbacks = WeakHashMap<PlayerRuntimeController, AudioSyncFallback>()
        private val allSubtitles = WeakHashMap<PlayerRuntimeController, Pair<String?, List<Subtitle>>>()

        /**
         * The add-on subtitles found for [runtime]'s current title before Nuvio hides languages
         * the user didn't ask for; the audio sync still uses those as references.
         */
        fun offerSubtitles(runtime: PlayerRuntimeController, subtitles: List<Subtitle>) {
            synchronized(allSubtitles) { allSubtitles[runtime] = runtime.currentVideoId to subtitles }
        }

        /** Settings persistence, the speech model and the log. Safe to call more than once. */
        fun initialize(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)
            AudioSyncSettings.installPersistence(
                load = { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null },
                save = { key, value -> preferences.edit().putBoolean(key, value).apply() },
            )
            AsrModel.initialize(appContext)
            SyncLog.initialize(appContext)
        }

        /**
         * The fallback of [runtime]'s current player and stream, created on first use and
         * replaced when either changes. Null without an ExoPlayer (e.g. the MPV engine).
         */
        fun of(runtime: PlayerRuntimeController): AudioSyncFallback? = synchronized(fallbacks) {
            initialize(runtime.context)
            val player = runtime._exoPlayer ?: return null
            val existing = fallbacks[runtime]
            if (existing != null && existing.player === player && existing.sourceUrl == runtime.currentStreamUrl) {
                return existing
            }
            existing?.release()
            AudioSyncFallback(runtime, player, runtime.currentStreamUrl).also { fallbacks[runtime] = it }
        }

        /** The player is closing: stop and free [runtime]'s fallback. */
        fun release(runtime: PlayerRuntimeController) {
            synchronized(fallbacks) { fallbacks.remove(runtime) }?.release()
        }

        /** Stops listening and any session, keeping the fallback for this player's next run. */
        fun stop(runtime: PlayerRuntimeController) {
            synchronized(fallbacks) { fallbacks[runtime] }?.stop()
        }

        private fun isLoopback(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }.getOrDefault(false)

        /**
         * [cues] placed on the media timeline by [model] (subtitle time -> media time); the
         * original cues when there is no mapping. Lines of a scene the release cuts are dropped,
         * as a subtitle delay would never show them either.
         */
        internal fun retime(cues: List<CuesWithTiming>, model: SubtitleSyncModel?): List<CuesWithTiming> {
            if (model == null) return cues
            return cues.mapNotNull { entry ->
                if (entry.startTimeUs == C.TIME_UNSET) return@mapNotNull entry
                val startUs = model.mediaTimeUs(entry.startTimeUs) ?: return@mapNotNull null
                val durationUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs - entry.startTimeUs
                    else -> C.TIME_UNSET
                }
                val scaledUs = if (durationUs == C.TIME_UNSET) durationUs else (durationUs * model.segmentAt(startUs / 1_000L).scale).toLong()
                CuesWithTiming(entry.cues, startUs, scaledUs)
            }.sortedBy { it.startTimeUs }
        }
    }
}

/**
 * Routes audio the player demuxes or plays to the fallback of the stream being played. The
 * player's extractors and audio output are created before the fallback, so they look it up here.
 */
internal object AudioSyncTaps {
    @Volatile
    private var active: AudioSubtitleSyncController? = null

    fun attach(controller: AudioSubtitleSyncController) {
        active = controller
    }

    fun detach(controller: AudioSubtitleSyncController) {
        if (active === controller) active = null
    }

    /** Copies the audio of [sourceKey]'s stream while it is demuxed (look-ahead). */
    fun wrapExtractors(factory: ExtractorsFactory, sourceKey: String): ExtractorsFactory =
        AudioSyncExtractorsFactory(factory, object : AudioSampleSink {
            private fun current() = active?.takeIf { it.currentSourceKey == sourceKey }

            override fun wantsSamples(format: Format): Boolean = current()?.wantsSamples(format) == true

            override fun onSample(format: Format, timeUs: Long, data: ByteArray, offset: Int, size: Int) {
                current()?.onSample(format, timeUs, data, offset, size)
            }

            override fun onDiscontinuity() {
                current()?.onDiscontinuity()
            }
        })

    /** Hears the player's own decoded audio where the look-ahead copy can't be decoded. */
    fun wrapAudioSink(sink: AudioSink): AudioSink = PlaybackAudioTap(sink, object : PlaybackPcmListener {
        override fun wantsPlaybackPcm(mediaTimeUs: Long, durationUs: Long): Boolean =
            active?.wantsPlaybackPcm(mediaTimeUs, durationUs) == true

        override fun onPlaybackPcm(mono: FloatArray, frames: Int, sampleRate: Int, mediaTimeUs: Long) {
            active?.onPlaybackPcm(mono, frames, sampleRate, mediaTimeUs)
        }
    })
}



private fun AudioSyncStatus.message(context: Context): String? = when (this) {
    AudioSyncStatus.Listening -> null // AutoSync's takeover toast already said so
    AudioSyncStatus.Withdrawn -> context.getString(R.string.player_audio_sync_withdrawn)
    is AudioSyncStatus.ModelDownloading -> context.getString(R.string.player_audio_sync_model_downloading, megabytes)
    is AudioSyncStatus.ModelNeedsWifi -> context.getString(R.string.player_audio_sync_model_needs_wifi, megabytes)
    is AudioSyncStatus.LiveOnly -> context.getString(
        R.string.player_audio_sync_live_only,
        mimeType.substringAfter('/').uppercase(),
    )
    is AudioSyncStatus.Estimated -> context.getString(R.string.player_audio_sync_estimated, formatOffset(offsetMs))
    is AudioSyncStatus.Synced -> context.getString(
        if (rateCorrected) R.string.player_audio_sync_synced_rate else R.string.player_audio_sync_synced,
        formatOffset(offsetMs),
    )
    is AudioSyncStatus.Adjusted -> context.getString(R.string.player_audio_sync_adjusted, formatOffset(offsetMs))
}

private fun formatOffset(offsetMs: Long): String {
    val sign = if (offsetMs < 0) "-" else "+"
    val tenths = (kotlin.math.abs(offsetMs) + 50) / 100
    return "$sign${tenths / 10}.${tenths % 10}s"
}
