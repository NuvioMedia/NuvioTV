@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player.audiosync

import android.content.Context
import android.net.Uri
import android.widget.Toast
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import kotlinx.coroutines.flow.combine
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
import com.nuvio.tv.ui.screens.player.PlayerSubtitleUtils
import com.nuvio.tv.ui.screens.player.buildAddonSubtitleTrackId
import com.nuvio.tv.ui.screens.player.currentPlaybackDurationMs
import com.nuvio.tv.ui.screens.player.currentPlaybackPositionMs
import com.nuvio.tv.ui.screens.player.isPlaybackCurrentlyPlaying
import com.nuvio.tv.ui.screens.player.isUsingMpvEngine
import com.nuvio.tv.ui.screens.player.keepMpvPlayingIfNeeded
import com.nuvio.tv.ui.screens.player.updateMpvAvailableTracks
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import com.nuvio.tv.ui.screens.player.commitPreparedSidecarSubtitle
import com.nuvio.tv.ui.screens.player.currentSidecarGenerationFor
import com.nuvio.tv.ui.screens.player.parseSidecarTimedCuesRobust
import com.nuvio.tv.ui.screens.player.rememberAddonSubtitleSelection
import com.nuvio.tv.ui.screens.player.setSubtitleDelayMs
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
import java.io.File
import java.util.Locale
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
 *
 * With the mpv engine ([player] is null) no audio passes through ExoPlayer, so the film is heard
 * only through spots sampled ahead of playback, and the mapping is shown the way AutoSync shows
 * its own result there: a retimed copy of the subtitle loaded as an external mpv track.
 */
internal class AudioSyncFallback private constructor(
    private val runtime: PlayerRuntimeController,
    /** The ExoPlayer that plays the stream; null when mpv plays it. */
    private val player: ExoPlayer?,
    /** The player object this fallback belongs to (the ExoPlayer or the mpv view). */
    private val engine: Any,
    private val sourceUrl: String,
) {
    private val isMpv = player == null
    private val appContext = runtime.context.applicationContext
    private val scope = runtime.scope
    private val controller = AudioSubtitleSyncController(
        context = appContext,
        // Subtitles are retimed in place, so the user's delay simply adds on top.
        manualDelayMs = { 0 },
        onStatus = ::onStatus,
        requestSwitch = ::replaceSubtitle,
    )

    /**
     * The subtitle being synced, with its original cues and the sidecar attachment it lives in
     * (ExoPlayer), or the retimed copy mpv shows for it ([mpvTrackId], null while the original shows).
     */
    private class Target(
        val url: String,
        val cues: List<CuesWithTiming>,
        val generation: Long,
        var mpvTrackId: Int? = null,
    )

    private val target = AtomicReference<Target?>(null)
    private var armed = false
    private var appliedModel: SubtitleSyncModel? = null
    private var ticker: Job? = null
    private var takeOverJob: Job? = null
    private var settingsJob: Job? = null
    private var candidatesJob: Job? = null
    private val resultToasts = AudioSyncResultToasts()
    private var pendingStatus: AudioSyncStatus? = null

    /** Playback time the current takeover has gone without any mapping. */
    private var unmatchedPlayedMs = 0L
    private var unmatchedReported = false

    private val trackListener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            controller.onAudioTrackSelected(tracks.selectedAudioFormat())
        }
    }

    init {
        controller.listensBeforeSession = false
        controller.spotOnly = isMpv
        if (isMpv) controller.spotOnlyAudioLanguage = mpvAudioLanguage()
        controller.enabled = AudioSyncSettings.fallbackEnabled.value
        controller.samplingMode = AudioSyncSettings.samplingMode.value
        controller.onSourceChanged(sourceUrl)
        controller.setSpotSource(
            forSourceKey = sourceUrl,
            url = sourceUrl,
            dataSourceFactory = PlayerPlaybackNetworking.createDataSourceFactory(appContext, runtime.currentHeaders),
            extractorsFactory = DefaultExtractorsFactory(),
            localEngine = runtime._uiState.value.isTorrentStream || isLoopback(sourceUrl),
        )
        if (player != null) {
            player.addListener(trackListener)
            controller.onAudioTrackSelected(player.currentTracks.selectedAudioFormat())
            AudioSyncTaps.attach(controller)
        }
        // Turned off in Settings mid-playback: the subtitle goes back to AutoSync's (original) timing.
        settingsJob = scope.launch {
            combine(AudioSyncSettings.fallbackEnabled, AutoSyncPreferences.enabled) { audio, auto -> audio && auto }
                .collect { enabled ->
                    if (!enabled) {
                        stop()
                        controller.enabled = false
                    }
                }
        }
    }

    /** AutoSync started analysing a subtitle: listen meanwhile, so a takeover starts with audio. */
    fun arm() {
        stop()
        resultToasts.reset()
        if (!AudioSyncSettings.fallbackEnabled.value) return
        controller.enabled = true
        controller.samplingMode = AudioSyncSettings.samplingMode.value
        controller.listensBeforeSession = true
        // Start an already-downloaded recognizer before subtitle discovery, downloads or
        // AutoSync's analysis. Recognition can then begin immediately on a later takeover.
        controller.prepareRecognizer()
        if (!armed) {
            // Defer title lookup until an AutoSync run actually starts, instead of stream setup.
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
        // mpv's audio is only heard through sampling; with sampling off there is nothing to sync to.
        if (isMpv && AudioSyncSettings.samplingMode.value == AudioSamplingMode.OFF) {
            SyncLog.i("audio sampling is off; the mpv engine has no other audio to sync to")
            disarm()
            return false
        }
        takeOverJob?.cancel()
        if (isMpv) {
            takeOverJob = scope.launch { takeOverMpv(url) }
            return true
        }
        takeOverJob = scope.launch {
            var waitedMs = 0L
            while (runtime.activeSidecarSubtitleKey == url && runtime.sidecarTimedCues.isEmpty() && waitedMs < CUES_WAIT_MS) {
                delay(CUES_POLL_MS)
                waitedMs += CUES_POLL_MS
            }
            val generation = runtime.currentSidecarGenerationFor(url)
            val cues = runtime.sidecarTimedCues
            if (generation == null || cues.isEmpty()) {
                if (runtime._uiState.value.selectedAddonSubtitle?.url == url && runtime.currentStreamUrl == sourceUrl) {
                    reportResult(success = false)
                }
                return@launch disarm()
            }
            target.set(Target(url, cues, generation))
            appliedModel = null
            unmatchedPlayedMs = 0L
            unmatchedReported = false
            startTicker()
            SyncLog.i("AutoSync kept the original timing of $url; syncing it to the audio")
            controller.startSession(url, cues)
        }
        return true
    }

    /** mpv: the subtitle's cues come from its file, as mpv shows them without the sidecar. */
    private suspend fun takeOverMpv(url: String) {
        val subtitle = runtime._uiState.value.selectedAddonSubtitle?.takeIf { it.url == url }
        val cues = if (subtitle == null) emptyList() else downloadCues(url, subtitle.headers.orEmpty())
        if (!isMpvShowing(url)) return disarm()
        if (cues.isEmpty()) {
            reportResult(success = false)
            return disarm()
        }
        val current = Target(url, cues, generation = 0L)
        target.set(current)
        appliedModel = null
        unmatchedPlayedMs = 0L
        unmatchedReported = false
        startTicker()
        SyncLog.i("AutoSync kept the original timing of $url on mpv; syncing it to sampled audio")
        controller.startSession(url, cues)
    }

    private suspend fun downloadCues(url: String, headers: Map<String, String>): List<CuesWithTiming> =
        withContext(Dispatchers.IO) {
            runCatching {
                val body = AutomaticSubtitleSync.downloadSubtitleBody(url = url, headers = headers)
                parseSidecarTimedCuesRobust(body, url).cues
            }.getOrDefault(emptyList())
        }

    /** mpv still plays this fallback's stream with [url] as the chosen add-on subtitle. */
    private fun isMpvShowing(url: String): Boolean =
        runtime.isUsingMpvEngine() && runtime.mpvView === engine && runtime.currentStreamUrl == sourceUrl &&
            runtime._uiState.value.selectedAddonSubtitle?.url == url

    /** The subtitle [current] was made for is still the one shown. */
    private fun stillShown(current: Target): Boolean =
        if (isMpv) isMpvShowing(current.url) else runtime.currentSidecarGenerationFor(current.url) == current.generation

    private fun mpvAudioLanguage(): String? =
        runtime._uiState.value.audioTracks.firstOrNull { it.isSelected }?.language

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
            if (isMpv) {
                showOriginalOnMpv(previous)
            } else {
                // Leave the subtitle on its original timing if it is still the one shown.
                runtime.commitPreparedSidecarSubtitle(
                    expectedCurrentUrl = previous.url,
                    newUrl = previous.url,
                    cues = previous.cues,
                    expectedGeneration = previous.generation,
                )
            }
        }
        appliedModel = null
        pendingStatus = null
        controller.listensBeforeSession = false
        controller.stopSession()
    }

    private fun release() {
        stop()
        settingsJob?.cancel()
        player?.removeListener(trackListener)
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
                if (current != null && !stillShown(current)) {
                    stop()
                    return@launch
                }
                if (isMpv) controller.spotOnlyAudioLanguage = mpvAudioLanguage()
                val durationMs = runtime.currentPlaybackDurationMs().takeIf { it > 0L && it != C.TIME_UNSET } ?: 0L
                controller.onPlaybackPosition(runtime.currentPlaybackPositionMs() ?: 0L, durationMs)
                val model = controller.currentModel()
                if (current != null && model !== appliedModel) {
                    appliedModel = model
                    apply(current, model)
                }
                val status = pendingStatus
                pendingStatus = null
                if (current != null && target.get() === current && status != null) {
                    when (status) {
                        is AudioSyncStatus.Synced, is AudioSyncStatus.Adjusted -> {
                            if (appliedModel != null && appliedModel === controller.currentModel()) {
                                AutoSyncSyncedSubtitle.mark(current.url)
                                reportResult(success = true)
                            } else if (controller.currentModel() != null) {
                                pendingStatus = status
                            }
                        }
                        AudioSyncStatus.Withdrawn, AudioSyncStatus.Failed -> {
                            if (controller.currentModel() == null) {
                                AutoSyncSyncedSubtitle.clear()
                                reportResult(success = false)
                            }
                        }
                        else -> Unit
                    }
                }
                // Still nothing after a while: say so once, and keep searching quietly. A later
                // match still shows its success.
                if (current != null && target.get() === current && !unmatchedReported && controller.currentModel() == null) {
                    if (runtime.isPlaybackCurrentlyPlaying()) unmatchedPlayedMs += TICK_MS
                    if (unmatchedPlayedMs >= UNMATCHED_REPORT_MS) {
                        unmatchedReported = true
                        SyncLog.i("no audio match after ${unmatchedPlayedMs / 1_000}s of playback; still searching")
                        reportResult(success = false)
                    }
                }
                delay(TICK_MS)
            }
        }
    }

    private suspend fun apply(current: Target, model: SubtitleSyncModel?) {
        if (isMpv) return applyOnMpv(current, model)
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

    /**
     * mpv: loads a retimed copy of the subtitle as an external track in place of the previous copy,
     * as AutoSync applies its own result there. Without a mapping the original is shown again.
     */
    private suspend fun applyOnMpv(current: Target, model: SubtitleSyncModel?) {
        if (model == null) {
            showOriginalOnMpv(current)
            return
        }
        val retimed = withContext(Dispatchers.Default) { retime(current.cues, model) }
        val path = withContext(Dispatchers.IO) { writeMpvCopy(current.url, retimed) }
        if (target.get() !== current || !isMpvShowing(current.url)) return
        val view = runtime.mpvView ?: return stop()
        val subtitle = runtime._uiState.value.addonSubtitles.firstOrNull { it.url == current.url }
            ?: runtime._uiState.value.selectedAddonSubtitle?.takeIf { it.url == current.url }
        val wasPlaying = runtime.isPlaybackCurrentlyPlaying()
        val trackId = path?.let {
            view.replaceExternalSubtitle(
                path = it,
                title = subtitle?.let(runtime::buildAddonSubtitleTrackId),
                language = subtitle?.lang?.let(PlayerSubtitleUtils::normalizeLanguageCode),
                replaceTrackId = current.mpvTrackId,
            )
        }
        if (trackId == null) {
            SyncLog.w("mpv did not accept the synced subtitle copy")
            return stop()
        }
        current.mpvTrackId = trackId
        runtime.keepMpvPlayingIfNeeded(wasPlaying)
        runtime.updateMpvAvailableTracks()
    }

    /**
     * mpv: drops the retimed copy of [previous]. When that copy is what mpv shows, the original
     * file is selected again first (mpv's `cached` sub-add reuses the track it already has).
     */
    private fun showOriginalOnMpv(previous: Target) {
        val trackId = previous.mpvTrackId ?: return
        previous.mpvTrackId = null
        val view = runtime.mpvView?.takeIf { it === engine } ?: return
        if (view.selectedSubtitleTrackId() == trackId && isMpvShowing(previous.url)) {
            val subtitle = runtime._uiState.value.selectedAddonSubtitle
            view.addAndSelectExternalSubtitle(
                url = previous.url,
                title = subtitle?.let(runtime::buildAddonSubtitleTrackId),
                language = subtitle?.lang?.let(PlayerSubtitleUtils::normalizeLanguageCode),
            )
        }
        view.removeSubtitleTrack(trackId)
        runtime.updateMpvAvailableTracks()
    }

    /** Writes [cues] as SRT next to AutoSync's mpv copies; returns its path, or null on failure. */
    private fun writeMpvCopy(url: String, cues: List<CuesWithTiming>): String? = runCatching {
        val text = toSrt(cues)
        val cacheDir = File(appContext.cacheDir, "subtitles").also { it.mkdirs() }
        val prefix = "mpv_audiosync_${Integer.toHexString(url.hashCode())}_"
        val file = File(cacheDir, "$prefix${Integer.toHexString(text.hashCode())}.srt")
        cacheDir.listFiles { candidate -> candidate.name.startsWith(prefix) && candidate != file }
            ?.forEach { it.delete() }
        file.writeText(text, Charsets.UTF_8)
        file.absolutePath
    }.onFailure { SyncLog.w("could not write synced subtitle copy: ${it.message}") }.getOrNull()

    /** Automatic switch to another subtitle that fits the audio, as AutoSync replaces a subtitle. */
    private fun replaceSubtitle(url: String) {
        scope.launch {
            val current = target.get() ?: return@launch
            val subtitle = runtime._uiState.value.addonSubtitles.firstOrNull { it.url == url } ?: return@launch
            val cues = downloadCues(url, subtitle.headers.orEmpty())
            if (cues.isEmpty() || target.get() !== current) return@launch
            if (isMpv) {
                if (!isMpvShowing(current.url)) return@launch
                AutoSyncSyncedSubtitle.clear()
                // The previous copy is replaced as soon as the handed-over mapping is applied.
                target.set(Target(url, cues, generation = 0L, mpvTrackId = current.mpvTrackId))
                appliedModel = null
                runtime._uiState.update { it.copy(selectedAddonSubtitle = subtitle, selectedSubtitleTrackIndex = -1) }
                if (runtime.isUserExplicitSubtitleSelection) runtime.rememberAddonSubtitleSelection(subtitle)
                runtime.setSubtitleDelayMs(targetMs = 0, showOverlay = false)
                controller.startSession(url, cues)
                return@launch
            }
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
            runtime.setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            // The new session adopts the mapping the switch was decided with.
            controller.startSession(url, cues)
        }
    }

    /** Only confirmed results reach the UI; later refinements stay silent. */
    private fun onStatus(status: AudioSyncStatus) {
        if (status !is AudioSyncStatus.Synced && status !is AudioSyncStatus.Adjusted &&
            status != AudioSyncStatus.Withdrawn && status != AudioSyncStatus.Failed) return
        val current = target.get() ?: return
        scope.launch {
            if (target.get() === current) pendingStatus = status
        }
    }

    private fun reportResult(success: Boolean) {
        if (!resultToasts.shouldShow(success)) return
        Toast.makeText(
            appContext,
            if (success) R.string.player_audio_sync_success else R.string.player_audio_sync_failed,
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        private const val TICK_MS = 250L
        private const val CUES_WAIT_MS = 5_000L
        private const val CUES_POLL_MS = 50L
        private const val UNMATCHED_REPORT_MS = 5 * 60_000L
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

        /** Settings persistence and the speech model. Safe to call more than once. */
        fun initialize(context: Context) {
            if (!initialized.compareAndSet(false, true)) return
            val appContext = context.applicationContext
            val preferences = appContext.getSharedPreferences("nuvio_audio_sync_settings", Context.MODE_PRIVATE)
            AudioSyncSettings.installPersistence(
                load = { key -> if (preferences.contains(key)) preferences.getBoolean(key, false) else null },
                save = { key, value -> preferences.edit().putBoolean(key, value).apply() },
                loadString = { key -> preferences.getString(key, null) },
                saveString = { key, value -> preferences.edit().putString(key, value).apply() },
            )
            AsrModel.initialize(appContext)
        }

        /**
         * The fallback of [runtime]'s current player (ExoPlayer or mpv) and stream, created on
         * first use and replaced when either changes. Null when the fallback is off.
         */
        fun of(runtime: PlayerRuntimeController): AudioSyncFallback? = synchronized(fallbacks) {
            initialize(runtime.context)
            if (!AudioSyncSettings.fallbackEnabled.value) return null
            val mpvView = if (runtime.isUsingMpvEngine()) runtime.mpvView else null
            val exoPlayer = if (mpvView == null) runtime._exoPlayer else null
            val engine: Any = mpvView ?: exoPlayer ?: return null
            val existing = fallbacks[runtime]
            if (existing != null && existing.engine === engine && existing.sourceUrl == runtime.currentStreamUrl) {
                return existing
            }
            existing?.release()
            AudioSyncFallback(runtime, exoPlayer, engine, runtime.currentStreamUrl).also { fallbacks[runtime] = it }
        }

        /** The player is closing: stop and free [runtime]'s fallback. */
        fun release(runtime: PlayerRuntimeController) {
            synchronized(fallbacks) { fallbacks.remove(runtime) }?.release()
            synchronized(allSubtitles) { allSubtitles.remove(runtime) }
        }

        /** Stops listening and any session, keeping the fallback for this player's next run. */
        fun stop(runtime: PlayerRuntimeController) {
            synchronized(fallbacks) { fallbacks[runtime] }?.stop()
        }

        private fun isLoopback(url: String): Boolean = runCatching {
            val host = Uri.parse(url).host.orEmpty().lowercase()
            host == "localhost" || host == "127.0.0.1" || host == "::1" || host == "[::1]"
        }.getOrDefault(false)

        /** [cues] as SubRip text; cues without a start are skipped, open-ended ones last 2 s. */
        internal fun toSrt(cues: List<CuesWithTiming>): String = buildString {
            var index = 1
            for (entry in cues) {
                if (entry.startTimeUs == C.TIME_UNSET) continue
                val text = entry.cues.mapNotNull { it.text?.toString()?.trim()?.takeIf(String::isNotEmpty) }
                    .joinToString("\n")
                if (text.isEmpty()) continue
                val endUs = when {
                    entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
                    entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
                    else -> entry.startTimeUs + 2_000_000L
                }
                append(index++).append('\n')
                append(srtTime(entry.startTimeUs)).append(" --> ").append(srtTime(endUs)).append('\n')
                append(text).append("\n\n")
            }
        }

        private fun srtTime(timeUs: Long): String {
            val totalMs = (timeUs / 1_000L).coerceAtLeast(0L)
            // Locale.ROOT: SubRip needs ASCII digits whatever the device language.
            return String.format(
                Locale.ROOT,
                "%02d:%02d:%02d,%03d",
                totalMs / 3_600_000L, totalMs / 60_000L % 60L, totalMs / 1_000L % 60L, totalMs % 1_000L,
            )
        }

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
