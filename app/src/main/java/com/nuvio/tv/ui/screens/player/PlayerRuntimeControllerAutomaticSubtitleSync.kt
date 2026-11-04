package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.extractor.ExtractorsFactory
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncAnalysisOutcome
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncCandidateScope
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncExtractorsFactory
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncLibassSubtitles
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncResolvedTimeline
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSubtitleCandidate
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSubtitleTextRetimer
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncSyncedSubtitle
import com.nuvio.tv.ui.screens.player.autosync.AutomaticSubtitleSync
import com.nuvio.tv.ui.screens.player.autosync.EmbeddedSubtitleTimelineLoader
import com.nuvio.tv.ui.screens.player.autosync.applyAutoSyncSidecarTimeline
import com.nuvio.tv.ui.screens.player.autosync.maxAlignmentShiftMs
import com.nuvio.tv.ui.screens.player.autosync.replaceAutoSyncSidecarSubtitle
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/** Thin TV adapter around the feature-owned Mobile AutoSync V2 pipeline. */
private val autoSyncToastHandler = Handler(Looper.getMainLooper())

private fun PlayerRuntimeController.showAutoSyncToast(
    message: String,
    duration: Int = Toast.LENGTH_SHORT,
) {
    autoSyncToastHandler.post {
        Toast.makeText(context, message, duration).show()
    }
}
/**
 * Wraps Nuvio's extractors so AutoSync can observe embedded subtitle timing (output is forwarded
 * unchanged), and starts AutoSync's embedded subtitle index download while the stream opens.
 */
internal fun PlayerRuntimeController.autoSyncExtractorsFactory(
    delegate: ExtractorsFactory,
    url: String,
    headers: Map<String, String>,
): ExtractorsFactory {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return delegate
    val factory = AutoSyncExtractorsFactory(delegate = delegate, sourceKey = url)
    prefetchAutoSyncIndex(url, headers)
    return factory
}

/**
 * Starts the embedded subtitle index download while the stream opens, so a later AutoSync run
 * finds it cached or joins the in-flight load instead of starting when a subtitle is selected.
 */
internal fun PlayerRuntimeController.prefetchAutoSyncIndex(
    url: String,
    headers: Map<String, String>,
) {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return
    EmbeddedSubtitleTimelineLoader.prefetch(scope, url, headers)
}

/** The user picked [subtitle]: check only that subtitle, never swap in another one. */
internal fun PlayerRuntimeController.runSelectedAutomaticSubtitleSync(subtitle: Subtitle) =
    maybeRunAutomaticSubtitleSync(subtitle, AutoSyncCandidateScope.SELECTED_ONLY)

internal fun PlayerRuntimeController.cancelAutomaticSubtitleSync() {
    automaticSubtitleSyncJob?.cancel()
    automaticSubtitleSyncJob = null
}

internal fun PlayerRuntimeController.maybeRunAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope = AutoSyncCandidateScope.STARTUP_SEARCH,
) {
    AutoSyncPreferences.ensureLoaded(context)
    if (!AutoSyncPreferences.isEnabled(context)) return
    if (selectedSubtitle.lang.isBlank()) return
    if (!currentStreamUrl.startsWith("http://", ignoreCase = true) &&
        !currentStreamUrl.startsWith("https://", ignoreCase = true)
    ) {
        return
    }
    if (
        candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH &&
        !AutoSyncPreferences.claimStartupRun(hashCode(), currentStreamUrl)
    ) {
        return
    }
    if (isUsingMpvEngine()) {
        runMpvAutomaticSubtitleSync(selectedSubtitle, candidateScope)
        return
    }

    val player = _exoPlayer ?: return
    val useLibass = requestedUseLibassByUser || activePlayerUsesLibass

    // ASS/SSA stays on libass's own path so its styling is kept; the result is applied there.
    if (useLibass && isLibassAddonSubtitle(selectedSubtitle)) {
        runLibassAutomaticSubtitleSync(selectedSubtitle, candidateScope)
        return
    }

    if (!canAttachAddonSubtitleViaSidecar(selectedSubtitle)) {
        return
    }

    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedUrl = selectedSubtitle.url
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    // One download feeds both the sidecar renderer and the analysis. It completes with null
    // on failure or cancellation so neither side can wait on it forever.
    val selectedBodyDeferred = CompletableDeferred<String?>()
    val started = startSidecarAddonSubtitle(
        subtitle = selectedSubtitle,
        rawBodyLoader = {
            selectedBodyDeferred.await()
                ?: throw IllegalStateException("Subtitle body unavailable")
        },
    )
    if (!started) {
        return
    }

    player.trackSelectionParameters = player.trackSelectionParameters
        .buildUpon()
        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        .build()

    automaticSubtitleSyncJob = scope.launch {
        launch {
            val body = try {
                AutomaticSubtitleSync.downloadSubtitleBody(
                    url = selectedUrl,
                    headers = selectedSubtitle.headers.orEmpty(),
                    languageHint = selectedSubtitle.lang,
                )
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 subtitle download failed", error)
                null
            }
            selectedBodyDeferred.complete(body)
        }
        // The user can switch to a built-in track, turn subtitles off or open another stream while
        // this runs; the fallbacks below must then leave their choice alone.
        fun stillRelevant(): Boolean =
            currentStreamUrl == sourceUrlAtStart &&
                _uiState.value.selectedAddonSubtitle?.url == selectedUrl

        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 start scope=${candidateScope.name} " +
                    "lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitleUrl = selectedUrl,
                selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                selectedSubtitleBodyDeferred = selectedBodyDeferred,
                preferredLanguage = selectedSubtitle.lang,
                alternativeSubtitles = if (candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH) {
                    candidatesAtStart.map { it.toAutoSyncCandidate() }
                } else {
                    emptyList()
                },
                alternativeSubtitlesProvider = if (
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
                ) {
                    { _uiState.value.addonSubtitles.map { it.toAutoSyncCandidate() } }
                } else {
                    null
                },
                onReferenceReady = {},
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
            )

            if (resolved == null) {
                if (!stillRelevant()) return@launch
                if (activeSidecarSubtitleKey == null) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                context.buildAutoSyncFailureToast(analysisOutcome)?.let(::showAutoSyncToast)
                return@launch
            }

            if (currentStreamUrl != sourceUrlAtStart) return@launch
            val activeSubtitleUrl = _uiState.value.selectedAddonSubtitle?.url
            if (activeSubtitleUrl != selectedUrl && activeSubtitleUrl != resolved.subtitleUrl) {
                return@launch
            }

            val chosenSubtitle = candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: selectedSubtitle.takeIf { it.url == resolved.subtitleUrl }
                ?: return@launch

            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferences.syncToleranceMs.value
            val withinToleranceMs = toleranceMs.takeIf {
                it > 0 &&
                    resolved.subtitleUrl == selectedUrl &&
                    resolved.timeline.maxAlignmentShiftMs() <= it
            }
            val applied = when {
                withinToleranceMs != null -> activeSidecarSubtitleKey == selectedUrl
                resolved.subtitleUrl == selectedUrl -> {
                    applyAutoSyncSidecarTimeline(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        url = selectedUrl,
                        timeline = resolved.timeline,
                    )
                }
                activeSidecarSubtitleKey == null &&
                    startSidecarAddonSubtitle(
                        subtitle = chosenSubtitle,
                        rawBodyLoader = resolved.subtitleBody?.let { body ->
                            suspend { body }
                        },
                    ) -> {
                    applyAutoSyncSidecarTimeline(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        url = resolved.subtitleUrl,
                        timeline = resolved.timeline,
                    )
                }
                else -> {
                    replaceAutoSyncSidecarSubtitle(
                        sidecar = this@maybeRunAutomaticSubtitleSync,
                        expectedCurrentUrl = selectedUrl,
                        url = resolved.subtitleUrl,
                        headers = resolved.subtitleHeaders,
                        rawBody = resolved.subtitleBody,
                        useLibass = useLibass,
                        timeline = resolved.timeline,
                    )
                }
            }

            if (!applied) {
                if (!stillRelevant()) return@launch
                if (activeSidecarSubtitleKey == null) {
                    startSidecarAddonSubtitle(selectedSubtitle)
                }
                return@launch
            }

            if (chosenSubtitle.url != selectedUrl) {
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = chosenSubtitle,
                        selectedSubtitleTrackIndex = -1,
                    )
                }
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            AutoSyncSyncedSubtitle.mark(chosenSubtitle.url)
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 failed", error)
            if (!stillRelevant()) return@launch
            if (activeSidecarSubtitleKey == null) {
                startSidecarAddonSubtitle(selectedSubtitle)
            }
        }
    }.also { job ->
        job.invokeOnCompletion { selectedBodyDeferred.complete(null) }
    }
}

/**
 * mpv path. mpv exposes no embedded subtitle timing while it plays, so only the container's own
 * subtitle index can serve as the reference. The result is applied by loading a copy of the
 * subtitle whose timestamps are rewritten, which mpv shows in place of the original track.
 */
private fun PlayerRuntimeController.runMpvAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope,
) {
    automaticSubtitleSyncJob?.cancel()

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedUrl = selectedSubtitle.url
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    automaticSubtitleSyncJob = scope.launch {
        fun streamUnchanged(): Boolean =
            currentStreamUrl == sourceUrlAtStart && isUsingMpvEngine()

        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 mpv start scope=${candidateScope.name} " +
                    "lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            val resolved = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitleUrl = selectedUrl,
                selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                preferredLanguage = selectedSubtitle.lang,
                alternativeSubtitles = if (candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH) {
                    candidatesAtStart.map { it.toAutoSyncCandidate() }
                } else {
                    emptyList()
                },
                alternativeSubtitlesProvider = if (
                    candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
                ) {
                    { _uiState.value.addonSubtitles.map { it.toAutoSyncCandidate() } }
                } else {
                    null
                },
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
                allowLiveReference = false,
            )

            // The plain selection loads the original file; let it land so it cannot replace
            // the synced copy afterwards.
            mpvAddonSubtitleSelectionJob?.join()
            if (!streamUnchanged()) return@launch
            val activeSubtitleUrl = _uiState.value.selectedAddonSubtitle?.url
            if (resolved == null) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 mpv no match outcome=$analysisOutcome")
                if (activeSubtitleUrl == selectedUrl) {
                    context.buildAutoSyncFailureToast(analysisOutcome)?.let(::showAutoSyncToast)
                }
                return@launch
            }
            if (activeSubtitleUrl != selectedUrl && activeSubtitleUrl != resolved.subtitleUrl) {
                return@launch
            }

            val chosenSubtitle = candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: return@launch

            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferences.syncToleranceMs.value
            val withinTolerance = toleranceMs > 0 &&
                resolved.subtitleUrl == selectedUrl &&
                resolved.timeline.maxAlignmentShiftMs() <= toleranceMs
            if (!withinTolerance) {
                val path = writeMpvAutoSyncedSubtitle(chosenSubtitle, resolved)
                if (!streamUnchanged() || _uiState.value.selectedAddonSubtitle?.url != activeSubtitleUrl) {
                    return@launch
                }
                val wasPlaying = isPlaybackCurrentlyPlaying()
                val added = path != null && mpvView?.addAndSelectExternalSubtitle(
                    url = path,
                    title = buildAddonSubtitleTrackId(chosenSubtitle),
                    language = PlayerSubtitleUtils.normalizeLanguageCode(chosenSubtitle.lang),
                ) == true
                if (!added) {
                    return@launch
                }
                keepMpvPlayingIfNeeded(wasPlaying)
            }

            if (chosenSubtitle.url != selectedUrl) {
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = chosenSubtitle,
                        selectedSubtitleTrackIndex = -1,
                    )
                }
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            if (!withinTolerance) updateMpvAvailableTracks()
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            AutoSyncSyncedSubtitle.mark(chosenSubtitle.url)
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 mpv applied id=${chosenSubtitle.id} " +
                    "switched=${chosenSubtitle.url != selectedUrl} keptTiming=$withinTolerance " +
                    "source=${resolved.timeline.alignmentSource} " +
                    "scale=${resolved.timeline.alignmentScale} " +
                    "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 mpv failed", error)
        }
    }
}

private fun PlayerRuntimeController.isLibassAddonSubtitle(subtitle: Subtitle): Boolean =
    PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url) == MimeTypes.TEXT_SSA &&
        _exoPlayer?.getAssHandlerCompat() != null

/**
 * ExoPlayer path for ASS/SSA add-on subtitles drawn by libass. The analysis is the same as for the
 * sidecar renderer, while libass keeps showing the original. A confident result is applied by
 * loading a copy of the subtitle whose timestamps are rewritten into libass's track for it
 * ([AutoSyncLibassSubtitles]), so styling is kept and the media source is not reloaded.
 *
 * The startup search tries ASS/SSA candidates first, so the styling is kept whenever one fits.
 * Only when none does are the other formats tried, and a fitting SRT/VTT is shown by the sidecar
 * renderer: a synced subtitle without styling beats a styled one that is out of sync.
 */
private fun PlayerRuntimeController.runLibassAutomaticSubtitleSync(
    selectedSubtitle: Subtitle,
    candidateScope: AutoSyncCandidateScope,
) {
    automaticSubtitleSyncJob?.cancel()
    val player = _exoPlayer ?: return

    val sourceUrlAtStart = currentStreamUrl
    val sourceHeadersAtStart = currentHeaders.toMap()
    val selectedUrl = selectedSubtitle.url
    fun Subtitle.isAss() = PlayerSubtitleUtils.mimeTypeFromUrl(url) == MimeTypes.TEXT_SSA
    val candidatesAtStart = (_uiState.value.addonSubtitles + selectedSubtitle)
        .distinctBy { it.url }
    val candidateByUrl = candidatesAtStart.associateBy { it.url }

    automaticSubtitleSyncJob = scope.launch {
        fun streamUnchanged(): Boolean =
            currentStreamUrl == sourceUrlAtStart && _exoPlayer === player && !isUsingMpvEngine()

        fun stillRelevant(): Boolean =
            streamUnchanged() && _uiState.value.selectedAddonSubtitle?.url == selectedUrl

        try {
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 libass start scope=${candidateScope.name} " +
                    "lang=${selectedSubtitle.lang} candidates=${candidatesAtStart.size}",
            )
            var analysisOutcome: AutoSyncAnalysisOutcome? = null
            val startupSearch = candidateScope == AutoSyncCandidateScope.STARTUP_SEARCH
            suspend fun search(ass: Boolean) = AutomaticSubtitleSync.findTimelineRetime(
                sourceKey = sourceUrlAtStart,
                sourceHeaders = sourceHeadersAtStart,
                selectedSubtitleUrl = selectedUrl,
                selectedSubtitleHeaders = selectedSubtitle.headers.orEmpty(),
                preferredLanguage = selectedSubtitle.lang,
                alternativeSubtitles = if (startupSearch) {
                    candidatesAtStart.filter { it.isAss() == ass }.map { it.toAutoSyncCandidate() }
                } else {
                    emptyList()
                },
                alternativeSubtitlesProvider = if (startupSearch) {
                    {
                        _uiState.value.addonSubtitles
                            .filter { it.isAss() == ass }
                            .map { it.toAutoSyncCandidate() }
                    }
                } else {
                    null
                },
                onAnalysisOutcome = { outcome -> analysisOutcome = outcome },
            )
            var resolved = search(ass = true)
            // No ASS/SSA fits: try the other formats, unless the video has nothing to compare with.
            if (
                resolved == null && startupSearch && streamUnchanged() &&
                analysisOutcome != AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS &&
                analysisOutcome != AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE &&
                _uiState.value.addonSubtitles.any { !it.isAss() }
            ) {
                resolved = search(ass = false)
            }

            if (!streamUnchanged()) return@launch
            val activeSubtitleUrl = _uiState.value.selectedAddonSubtitle?.url
            if (resolved == null) {
                Log.d(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 libass no match outcome=$analysisOutcome")
                if (activeSubtitleUrl == selectedUrl) {
                    context.buildAutoSyncFailureToast(analysisOutcome)?.let(::showAutoSyncToast)
                }
                return@launch
            }
            if (activeSubtitleUrl != selectedUrl && activeSubtitleUrl != resolved.subtitleUrl) {
                return@launch
            }

            val chosenSubtitle = candidateByUrl[resolved.subtitleUrl]
                ?: _uiState.value.addonSubtitles.firstOrNull { it.url == resolved.subtitleUrl }
                ?: return@launch

            // A confident match whose whole-film correction is within the user's tolerance keeps
            // the selected subtitle's original timing instead of retiming it.
            val toleranceMs = AutoSyncPreferences.syncToleranceMs.value
            val withinTolerance = toleranceMs > 0 &&
                resolved.subtitleUrl == selectedUrl &&
                resolved.timeline.maxAlignmentShiftMs() <= toleranceMs
            val applied = withinTolerance || applyLibassAutoSyncResult(
                player = player,
                selectedSubtitle = selectedSubtitle,
                chosenSubtitle = chosenSubtitle,
                resolved = resolved,
                stillApplicable = {
                    streamUnchanged() &&
                        _uiState.value.selectedAddonSubtitle?.url.let {
                            it == selectedUrl || it == chosenSubtitle.url
                        }
                },
            )
            if (!applied) {
                return@launch
            }

            if (chosenSubtitle.url != selectedUrl) {
                _uiState.update {
                    it.copy(
                        selectedAddonSubtitle = chosenSubtitle,
                        selectedSubtitleTrackIndex = -1,
                    )
                }
                rememberAddonSubtitleSelection(chosenSubtitle)
            }
            if (!resolved.reused) setSubtitleDelayMs(targetMs = 0, showOverlay = false)
            AutoSyncSyncedSubtitle.mark(chosenSubtitle.url)
            Log.d(
                PlayerRuntimeController.TAG,
                "AUTO_SYNC_V2 libass applied id=${chosenSubtitle.id} " +
                    "switched=${chosenSubtitle.url != selectedUrl} keptTiming=$withinTolerance " +
                    "source=${resolved.timeline.alignmentSource} " +
                    "scale=${resolved.timeline.alignmentScale} " +
                    "interceptMs=${resolved.timeline.alignmentInterceptMs.toLong()}",
            )
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "AUTO_SYNC_V2 libass failed", error)
        }
    }
}

/**
 * Applies [resolved] for the libass path. An ASS/SSA result is retimed as text and loaded into
 * libass's track, after Nuvio's own switch when AutoSync picked another ASS file. An SRT/VTT result
 * (no ASS/SSA fitted) is shown by the sidecar renderer and retimed there; if that fails, the
 * selected ASS subtitle is put back, so the viewer is never left without a subtitle.
 */
private suspend fun PlayerRuntimeController.applyLibassAutoSyncResult(
    player: ExoPlayer,
    selectedSubtitle: Subtitle,
    chosenSubtitle: Subtitle,
    resolved: AutoSyncResolvedTimeline,
    stillApplicable: () -> Boolean,
): Boolean {
    val selectedUrl = selectedSubtitle.url
    if (PlayerSubtitleUtils.mimeTypeFromUrl(chosenSubtitle.url) != MimeTypes.TEXT_SSA) {
        if (!canAttachAddonSubtitleViaSidecar(chosenSubtitle) || !stillApplicable()) return false
        disableSubtitles()
        pendingAddonSubtitleLanguage = null
        pendingAddonSubtitleTrackId = null
        pendingAudioSelectionAfterSubtitleRefresh = null
        val applied = startSidecarAddonSubtitle(
            subtitle = chosenSubtitle,
            rawBodyLoader = resolved.subtitleBody?.let { body -> suspend { body } },
        ) && applyAutoSyncSidecarTimeline(
            sidecar = this,
            url = chosenSubtitle.url,
            timeline = resolved.timeline,
        )
        if (!applied) {
            stopSidecarAddonSubtitle()
            selectAddonSubtitle(selectedSubtitle)
        }
        return applied
    }

    val assHandler = player.getAssHandlerCompat() ?: return false
    val body = resolved.subtitleBody
        ?: downloadSubtitleBody(chosenSubtitle.url, chosenSubtitle.lang, chosenSubtitle.headers)
    val retimed = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
        AutoSyncSubtitleTextRetimer.retime(
            body = SubtitleMojibakeSanitizer.sanitize(body).toString(),
            timeline = resolved.timeline,
        )
    } ?: return false
    if (!stillApplicable()) return false
    if (chosenSubtitle.url != selectedUrl) selectAddonSubtitle(chosenSubtitle)
    AutoSyncLibassSubtitles.apply(
        assHandler = assHandler,
        trackId = buildAddonSubtitleTrackId(chosenSubtitle),
        retimedText = retimed,
    )
    return true
}

/**
 * Writes [subtitle] with the AutoSync timeline applied next to the plain mpv subtitle cache, and
 * returns its path, or null when its format cannot be retimed. The name follows the content, so
 * mpv's `cached` sub-add reuses the track when the same result is applied again.
 */
private suspend fun PlayerRuntimeController.writeMpvAutoSyncedSubtitle(
    subtitle: Subtitle,
    resolved: AutoSyncResolvedTimeline,
): String? {
    val body = resolved.subtitleBody
        ?: downloadSubtitleBody(subtitle.url, subtitle.lang, subtitle.headers)
    val retimed = withContext(AutomaticSubtitleSync.autoSyncDispatcher) {
        AutoSyncSubtitleTextRetimer.retime(
            body = SubtitleMojibakeSanitizer.sanitize(body).toString(),
            timeline = resolved.timeline,
        )
    } ?: return null
    return withContext(Dispatchers.IO) {
        val cacheDir = File(context.cacheDir, "subtitles").also { it.mkdirs() }
        val prefix = "mpv_autosync_${Integer.toHexString(subtitle.id.hashCode())}_"
        val extension = when {
            retimed.trimStart().startsWith("WEBVTT") -> "vtt"
            retimed.contains("[Events]", ignoreCase = true) -> "ass"
            else -> "srt"
        }
        val file = File(cacheDir, "$prefix${Integer.toHexString(retimed.hashCode())}.$extension")
        cacheDir.listFiles { candidate -> candidate.name.startsWith(prefix) && candidate != file }
            ?.forEach { it.delete() }
        file.writeText(retimed, Charsets.UTF_8)
        file.absolutePath
    }
}

private fun Subtitle.toAutoSyncCandidate() = AutoSyncSubtitleCandidate(
    url = url,
    language = lang,
    name = addonName.ifBlank { id },
)

/**
 * The one AutoSync result worth telling the viewer: the subtitle was checked against this video's
 * own subtitles and does not fit them, so it stays out of sync with this release, and another
 * stream may fit it. Any other failure is technical or inconclusive and stays silent; the viewer
 * can still adjust the timing by hand.
 */
private fun Context.buildAutoSyncFailureToast(analysisOutcome: AutoSyncAnalysisOutcome?): String? =
    when (analysisOutcome) {
        AutoSyncAnalysisOutcome.NO_FIT -> getString(R.string.autosync_toast_no_fit)
        AutoSyncAnalysisOutcome.SUBTITLE_UNAVAILABLE,
        AutoSyncAnalysisOutcome.NO_SUBTITLE_TRACKS,
        AutoSyncAnalysisOutcome.NO_USABLE_REFERENCE,
        AutoSyncAnalysisOutcome.INCONCLUSIVE,
        null,
        -> null
    }
