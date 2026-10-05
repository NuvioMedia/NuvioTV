package com.nuvio.tv.ui.screens.player

import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.data.repository.PlaybackIssueErrorInput
import `is`.xyz.mpv.MPV
import `is`.xyz.mpv.MPVNode
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

private const val MPV_END_FILE_REASON_ERROR = "error"

private const val MPV_ERROR_LOADING_FAILED = 13
private const val MPV_ERROR_AO_INIT_FAILED = 14
private const val MPV_ERROR_VO_INIT_FAILED = 15
private const val MPV_ERROR_NOTHING_TO_PLAY = 16
private const val MPV_ERROR_UNKNOWN_FORMAT = 17
private const val MPV_ERROR_GENERIC = 20

private const val MPV_MAX_AUTO_RETRIES = 2
private const val MPV_RETRY_DELAY_MS = 1_500L
private const val MPV_STABLE_PROGRESS_RESET_DELAY_MS = 5_000L
private const val MPV_ERROR_LOG_LINES = 6

private val MPV_HTTP_ERROR_REGEX = Regex("HTTP error (\\d{3})(?:\\s+(.+))?", RegexOption.IGNORE_CASE)
private val MPV_URL_REGEX = Regex("https?://\\S+")
private val MPV_FFMPEG_ADDR_REGEX = Regex("\\s*@\\s*0x[0-9a-fA-F]+")
private val MPV_DIAGNOSTIC_LOG_REGEX = Regex(
    "codec|decoder|hwdec|mediacodec|hwaccel|profile|pix_fmt|pixel format|unsupported|not supported",
    RegexOption.IGNORE_CASE
)
private val MPV_INFO_CAUSE_REGEX = Regex(
    "^VO:|^AO:|selected video codec|selected audio codec|using hardware decoding|falling back to software",
    RegexOption.IGNORE_CASE
)
private val MPV_OUTPUT_CAUSE_REGEX = Regex(
    "video chain|audio chain|swapchain|cannot convert|could not initialize|failed to initialize|failed \\(re\\)creating|hw format|no video|no sound|no audio|" +
        "connection refused|timed out|timeout|handshake|invalid data|unknown format|recognize file format|protocol not found|i/o error|input/output error|" +
        "decoding frame|certificate|moov atom|ebml|avformat_open_input|initcheck|name resolution|unreachable|permission denied|video conversion",
    RegexOption.IGNORE_CASE
)

internal data class MpvEndFile(
    val reason: String,
    val fileError: String?,
    val playlistEntryId: Long?,
) {
    val isPlaybackError: Boolean
        get() = reason.equals(MPV_END_FILE_REASON_ERROR, ignoreCase = true)
}

internal fun parseMpvEndFile(data: MPVNode): MpvEndFile {
    val map = data.asMap().orEmpty()
    return MpvEndFile(
        reason = map["reason"]?.asString()?.trim().orEmpty(),
        fileError = map["file_error"]?.asString()?.trim()?.takeIf { it.isNotEmpty() },
        playlistEntryId = map["playlist_entry_id"]?.asInt()
    )
}

internal fun isStaleMpvEndFile(activePlaylistEntryId: Long?, endedPlaylistEntryId: Long?): Boolean {
    if (activePlaylistEntryId == null || endedPlaylistEntryId == null) return false
    return activePlaylistEntryId != endedPlaylistEntryId
}

internal fun mpvErrorCodeForFileError(fileError: String?): Int = when (fileError?.lowercase()) {
    "loading failed" -> MPV_ERROR_LOADING_FAILED
    "audio output initialization failed" -> MPV_ERROR_AO_INIT_FAILED
    "video output initialization failed" -> MPV_ERROR_VO_INIT_FAILED
    "unrecognized file format" -> MPV_ERROR_UNKNOWN_FORMAT
    "no audio or video data played" -> MPV_ERROR_NOTHING_TO_PLAY
    null -> MPV_ERROR_LOADING_FAILED
    else -> MPV_ERROR_GENERIC
}

internal class MpvEventRelay(
    private val controller: PlayerRuntimeController,
    private val epoch: Long
) : MPV.EventObserver, MPV.LogObserver {

    val active = AtomicBoolean(true)

    override fun event(eventId: Int, data: MPVNode) {
        if (!active.get() || epoch != controller.mpvEventRelayEpoch) return
        controller.scope.launch {
            if (!active.get() || epoch != controller.mpvEventRelayEpoch) return@launch
            controller.onMpvEvent(eventId, data)
        }
    }

    override fun logMessage(prefix: String, level: Int, text: String) {
        if (!active.get() || epoch != controller.mpvEventRelayEpoch) return
        controller.scope.launch {
            if (!active.get() || epoch != controller.mpvEventRelayEpoch) return@launch
            controller.onMpvLogLine(prefix, level, text)
        }
    }

    override fun eventProperty(property: String) = Unit
    override fun eventProperty(property: String, value: Long) = Unit
    override fun eventProperty(property: String, value: Boolean) = Unit
    override fun eventProperty(property: String, value: String) = Unit
    override fun eventProperty(property: String, value: Double) = Unit
    override fun eventProperty(property: String, value: MPVNode) = Unit
}

internal fun PlayerRuntimeController.registerMpvEventRelay(view: NuvioMpvSurfaceView) {
    unregisterMpvEventRelay()
    mpvEventRelayEpoch += 1
    mpvErrorRecoveryArmed = false
    mpvActivePlaylistEntryId = null
    mpvLastErrorLogLine = null
    mpvLastFileError = null
    resetMpvStartupWatchdog()
    val relay = MpvEventRelay(this, mpvEventRelayEpoch)
    mpvEventRelay = relay
    runCatching { view.mpv.addObserver(relay) }
    runCatching { view.mpv.addLogObserver(relay) }
}

internal fun PlayerRuntimeController.unregisterMpvEventRelay() {
    val relay = mpvEventRelay ?: return
    mpvEventRelay = null
    relay.active.set(false)
    mpvView?.let { view ->
        runCatching { view.mpv.removeObserver(relay) }
        runCatching { view.mpv.removeLogObserver(relay) }
    }
}

internal fun PlayerRuntimeController.onMpvEvent(eventId: Int, data: MPVNode) {
    when (eventId) {
        MPV.mpvEvent.MPV_EVENT_START_FILE -> onMpvStartFile(data)
        MPV.mpvEvent.MPV_EVENT_END_FILE -> onMpvEndFile(data)
        MPV.mpvEvent.MPV_EVENT_SHUTDOWN -> onMpvCoreShutdown()
        else -> Unit
    }
}

private fun PlayerRuntimeController.onMpvStartFile(data: MPVNode) {
    mpvActivePlaylistEntryId = data.asMap()?.get("playlist_entry_id")?.asInt()
    mpvIdleActiveTicks = 0
}

private fun PlayerRuntimeController.onMpvEndFile(data: MPVNode) {
    val event = parseMpvEndFile(data)
    playbackAnalyticsDiagnostics.recordRawEventLine(
        "MPV_END_FILE: reason=${event.reason.ifBlank { "n/a" }} " +
            "file_error=${event.fileError ?: "n/a"} entry=${event.playlistEntryId ?: -1}"
    )
    if (!event.isPlaybackError) return
    if (isStaleMpvEndFile(mpvActivePlaylistEntryId, event.playlistEntryId)) return

    mpvLastFileError = event.fileError
    handleMpvPlaybackError(
        source = "end_file",
        mpvErrorCode = mpvErrorCodeForFileError(event.fileError)
    )
}

private fun PlayerRuntimeController.onMpvCoreShutdown() {
    playbackAnalyticsDiagnostics.recordRawEventLine("MPV_SHUTDOWN: core terminated")
    if (hasRenderedFirstFrame) return
    if (currentStreamUrl.isBlank()) return
    handleMpvPlaybackError(
        source = "shutdown",
        mpvErrorCode = mpvErrorCodeForFileError(mpvLastFileError)
    )
}

internal fun PlayerRuntimeController.onMpvLogLine(prefix: String, level: Int, text: String) {
    val isHttp = MPV_HTTP_ERROR_REGEX.containsMatchIn(text)
    if (level > MPV.mpvLogLevel.MPV_LOG_LEVEL_INFO) return
    val isInfo = level > MPV.mpvLogLevel.MPV_LOG_LEVEL_WARN
    if (isInfo && !mpvInfoLineIsUseful(text)) return
    if (!isInfo && level > MPV.mpvLogLevel.MPV_LOG_LEVEL_ERROR && !isHttp && !mpvWarnLineIsUseful(prefix, text)) return
    val body = text.replace(MPV_URL_REGEX, "").trim().take(300)
    if (body.isEmpty()) return
    val line = if (prefix.isBlank()) body else "[${prefix.trim()}] $body"
    mpvLastErrorLogLine = rememberMpvErrorLog(mpvLastErrorLogLine, line)
    playbackAnalyticsDiagnostics.recordRawEventLine("MPV_LOG: $line".take(300))
}

internal fun PlayerRuntimeController.handleMpvPlaybackError(
    source: String,
    mpvErrorCode: Int = MPV_ERROR_GENERIC,
    detailedErrorOverride: String? = null,
    allowEngineFailover: Boolean = true
) {
    if (!isUsingMpvEngine()) return
    if (isReleasingPlayer) return
    if (startupEngineFailoverTriggered) return
    if (mpvErrorRecoveryArmed) return
    if (_uiState.value.error != null) return
    if (!mpvErrorHandlingInProgress.compareAndSet(false, true)) return
    mpvErrorRecoveryArmed = true
    mpvStableProgressResetJob?.cancel()
    mpvStableProgressResetJob = null

    try {
        val httpCode = mpvHttpStatusCodeFromLog()
        val logLine = if (httpCode == null) {
            listOfNotNull(
                mpvLastErrorLogLine?.takeIf { it.isNotBlank() },
                mpvCodecContextLine()
            ).joinToString("\n").ifBlank { null }
        } else {
            mpvLastErrorLogLine
        }
        val fallback = context.getString(R.string.player_error_playback_fallback)
        val statusExplanation = if (httpCode == null && detailedErrorOverride == null) {
            mpvNonHttpExplanationText(context, mpvLastFileError, mpvLastErrorLogLine)
        } else {
            ""
        }
        val technical = mpvDirectErrorMessage(
            fileError = mpvLastFileError,
            logLine = logLine,
            fallback = fallback,
            httpExplanation = if (httpCode != null && detailedErrorOverride == null) {
                httpStatusExplanation(context, httpCode)
            } else {
                ""
            },
            statusExplanation = statusExplanation
        )
        val detailedError = when {
            detailedErrorOverride == null -> technical
            technical.isBlank() ||
                technical.equals(fallback, ignoreCase = true) ||
                detailedErrorOverride.contains(technical) -> detailedErrorOverride
            else -> "$detailedErrorOverride\n\n$technical"
        }
        Log.e(
            PlayerRuntimeController.TAG,
            "MPV_PLAYBACK_ERROR: source=$source code=$mpvErrorCode detail=$detailedError"
        )
        playbackAnalyticsDiagnostics.recordRawEventLine(
            "MPV_PLAYBACK_ERROR: source=$source mpvErrorCode=$mpvErrorCode " +
                "httpStatus=${mpvHttpStatusCodeFromLog()} detail=${detailedError.take(200)}"
        )
        lastPlaybackIssueError = PlaybackIssueErrorInput(
            displayMessage = detailedError,
            errorCode = mpvErrorCode,
            errorCodeName = mpvErrorCodeName(mpvErrorCode),
            exceptionClass = "libmpv",
            causeClass = source,
            causeMessage = detailedError.takeIf { it.isNotBlank() },
            httpStatus = mpvHttpStatusCodeFromLog()
        )
        lastPlaybackDiagnosticsForReport =
            lastPlaybackDiagnosticsForReport.copy(result = "Error: $detailedError")

        val startupFailed = !hasRenderedFirstFrame
        if (startupFailed &&
            maybeAutoSwitchInternalPlayerOnStartupError(
                detailedError = detailedError,
                allowEngineFailover = allowEngineFailover
            )
        ) {
            return
        }

        val savedPosition = if (startupFailed) {
            0L
        } else {
            mpvView?.currentPositionMs()?.coerceAtLeast(0L) ?: 0L
        }
        if (attemptMpvAutoRetry(detailedError = detailedError, savedPosition = savedPosition)) return

        finishLoadingDiagnostics("mpv_error")
        cancelNextEpisodeAutoPlayOnFatalError()
        _uiState.update {
            it.copy(
                error = detailedError,
                showSwitchToMpvErrorAction = false,
                showLoadingOverlay = false,
                showPauseOverlay = false,
                isBuffering = false,
                loadingIssueReportVisible = false,
                loadingIssueElapsedMs = 0L,
                playbackEnded = false,
                postPlayMode = null
            )
        }
    } finally {
        mpvErrorHandlingInProgress.set(false)
    }
}

private fun PlayerRuntimeController.attemptMpvAutoRetry(
    detailedError: String,
    savedPosition: Long
): Boolean {
    if (errorRetryCount >= MPV_MAX_AUTO_RETRIES) return false

    val paused = userPausedManually
    val attempt = errorRetryCount
    errorRetryCount++

    Log.w(
        PlayerRuntimeController.TAG,
        "MPV auto-retry ${attempt + 1}/$MPV_MAX_AUTO_RETRIES after ${MPV_RETRY_DELAY_MS}ms for: $detailedError"
    )

    errorRetryJob?.cancel()
    errorRetryJob = scope.launch {
        showRecoveryOverlay()
        delay(MPV_RETRY_DELAY_MS)
        releasePlayer(flushPlaybackState = false)
        if (savedPosition > 0L) {
            _uiState.update { it.copy(pendingSeekPosition = savedPosition) }
        }
        initializePlayer(currentStreamUrl, currentHeaders, startPaused = paused)
    }
    return true
}

internal fun PlayerRuntimeController.maybeRunMpvStartupWatchdog(view: NuvioMpvSurfaceView) {
    if (hasRenderedFirstFrame) return

    val enabled = _uiState.value.error == null &&
        !isReleasingPlayer &&
        !startupEngineFailoverTriggered &&
        !mpvErrorRecoveryArmed &&
        currentStreamUrl.isNotBlank() &&
        !isInBackground &&
        !userPausedManually
    val waitingForSurface = view.hasPendingInitialMedia
    var idleActive = false
    var cacheProgressing = false
    if (enabled && !waitingForSurface) {
        idleActive = runCatching { view.mpv.getPropertyBoolean("idle-active") }.getOrNull() == true
        if (!idleActive) {
            val cacheSec = runCatching { view.demuxerCacheDurationSec() }.getOrDefault(0.0)
            cacheProgressing = MpvStartupWatchdogPolicy.cacheIsProgressing(mpvLastDemuxerCacheSec, cacheSec)
            if (cacheSec > mpvLastDemuxerCacheSec) {
                mpvLastDemuxerCacheSec = cacheSec
            }
        }
    }

    val step = MpvStartupWatchdogPolicy.step(
        MpvStartupWatchdogPolicy.Input(
            enabled = enabled,
            waitingForSurface = waitingForSurface,
            idleActive = idleActive,
            cacheProgressing = cacheProgressing,
            counters = MpvStartupWatchdogPolicy.Counters(
                surfaceWaitTicks = mpvSurfaceWaitTicks,
                idleTicks = mpvIdleActiveTicks,
                stallTicks = mpvStartupStallTicks,
                absoluteTicks = mpvStartupAbsoluteTicks
            )
        )
    )
    mpvSurfaceWaitTicks = step.counters.surfaceWaitTicks
    mpvIdleActiveTicks = step.counters.idleTicks
    mpvStartupStallTicks = step.counters.stallTicks
    mpvStartupAbsoluteTicks = step.counters.absoluteTicks

    when (step.action) {
        MpvStartupWatchdogPolicy.Action.Continue -> Unit
        MpvStartupWatchdogPolicy.Action.SurfaceTimeout -> handleMpvPlaybackError(
            source = "surface_timeout",
            mpvErrorCode = MPV_ERROR_GENERIC,
            detailedErrorOverride = context.getString(R.string.player_error_mpv_surface_failed)
        )
        MpvStartupWatchdogPolicy.Action.IdleError -> handleMpvPlaybackError(
            source = "idle_active",
            mpvErrorCode = MPV_ERROR_LOADING_FAILED
        )
        MpvStartupWatchdogPolicy.Action.StallTimeout,
        MpvStartupWatchdogPolicy.Action.AbsoluteTimeout -> handleMpvPlaybackError(
            source = if (step.action == MpvStartupWatchdogPolicy.Action.StallTimeout) {
                "startup_stall"
            } else {
                "startup_absolute"
            },
            mpvErrorCode = MPV_ERROR_LOADING_FAILED
        )
    }
}

internal fun PlayerRuntimeController.resetMpvStartupWatchdog() {
    mpvSurfaceWaitTicks = 0
    mpvIdleActiveTicks = 0
    mpvStartupStallTicks = 0
    mpvStartupAbsoluteTicks = 0
    mpvLastDemuxerCacheSec = 0.0
}

internal fun PlayerRuntimeController.scheduleMpvStableProgressReset() {
    mpvStableProgressResetJob?.cancel()
    mpvStableProgressResetJob = scope.launch {
        delay(MPV_STABLE_PROGRESS_RESET_DELAY_MS)
        if (hasRenderedFirstFrame &&
            isUsingMpvEngine() &&
            isPlaybackCurrentlyPlaying() &&
            !mpvErrorRecoveryArmed &&
            _uiState.value.error == null
        ) {
            resetErrorRetryState()
        }
    }
}

private fun PlayerRuntimeController.mpvHttpStatusCodeFromLog(): Int? {
    return mpvLastErrorLogLine
        ?.let { MPV_HTTP_ERROR_REGEX.find(it) }
        ?.groupValues
        ?.getOrNull(1)
        ?.toIntOrNull()
        ?.takeIf { it in 100..599 }
}

private fun PlayerRuntimeController.mpvCodecContextLine(): String? {
    val mpv = mpvView?.mpv ?: return null
    fun prop(name: String): String? = runCatching {
        mpv.getPropertyString(name)
            ?.trim()
            ?.substringBefore(" (")
            ?.trim()
            ?.takeIf { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) && !it.equals("no", ignoreCase = true) }
    }.getOrNull()
    val existing = mpvLastErrorLogLine.orEmpty()
    val width = runCatching { mpv.getPropertyInt("width") }.getOrNull()?.takeIf { it > 0 }
    val height = runCatching { mpv.getPropertyInt("height") }.getOrNull()?.takeIf { it > 0 }
    val resolution = if (width != null && height != null) "${width}x$height" else null
    val rate = runCatching { mpv.getPropertyInt("audio-params/samplerate") }.getOrNull()?.takeIf { it > 0 }
    val channels = runCatching { mpv.getPropertyInt("audio-params/channel-count") }.getOrNull()?.takeIf { it > 0 }
    val parts = listOfNotNull(
        fresh(existing, prop("file-format")),
        fresh(existing, prop("current-vo") ?: prop("vo"), label = "vo"),
        fresh(existing, prop("video-codec"), label = "video"),
        fresh(existing, resolution),
        fresh(existing, mpvFpsLabel(prop("container-fps"))),
        fresh(existing, prop("video-format") ?: prop("video-params/pixelformat")),
        fresh(existing, prop("current-tracks/video/decoder-desc"), label = "decoder"),
        fresh(existing, prop("audio-codec"), label = "audio"),
        fresh(existing, rate?.let { "${it}Hz" }),
        fresh(existing, channels?.let { "${it}ch" }),
        fresh(existing, prop("hwdec-current") ?: prop("hwdec"), label = "hwdec")
    )
    return parts.takeIf { it.isNotEmpty() }?.joinToString(", ")
}

private fun fresh(existing: String, token: String?, label: String = ""): String? {
    val value = token?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (existing.contains(value, ignoreCase = true)) return null
    return if (label.isEmpty()) value else "$label $value"
}

internal fun rememberMpvErrorLog(current: String?, line: String): String {
    val lines = current
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toMutableList()
        ?: mutableListOf()
    if (MPV_HTTP_ERROR_REGEX.containsMatchIn(line)) {
        lines.removeAll { MPV_HTTP_ERROR_REGEX.containsMatchIn(it) }
        lines.add(0, line)
    } else if (lines.none { it.equals(line, ignoreCase = true) }) {
        lines.add(line)
    }
    val http = lines.filter { MPV_HTTP_ERROR_REGEX.containsMatchIn(it) }
    val pinned = lines.filter { mpvLogTextIsCause(it) && !MPV_HTTP_ERROR_REGEX.containsMatchIn(it) }
        .take(MPV_ERROR_LOG_LINES)
    val other = lines.filter {
        !MPV_HTTP_ERROR_REGEX.containsMatchIn(it) &&
            !mpvLogTextIsCause(it) &&
            !it.contains("ytdl_hook", ignoreCase = true)
    }
    val hook = lines.filter { it.contains("ytdl_hook", ignoreCase = true) }
    val room = (MPV_ERROR_LOG_LINES - pinned.size).coerceAtLeast(0)
    val detail = if (http.isNotEmpty() || pinned.isNotEmpty() || other.isNotEmpty()) {
        pinned + other.takeLast(room)
    } else {
        hook.takeLast(MPV_ERROR_LOG_LINES)
    }
    return (http + detail).joinToString("\n")
}

internal fun mpvDirectErrorMessage(
    fileError: String?,
    logLine: String?,
    fallback: String,
    httpExplanation: String = "",
    statusExplanation: String = ""
): String {
    val lines = logLine
        ?.lineSequence()
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?.toList()
        .orEmpty()
    val http = lines.firstNotNullOfOrNull { MPV_HTTP_ERROR_REGEX.find(it) }
    val details = lines.mapNotNull { mpvDisplayDetail(it) }
    val file = fileError?.trim()?.takeIf { it.isNotEmpty() }
    if (http != null) {
        val code = http.groupValues[1]
        val reason = http.groupValues.getOrNull(2)
            ?.replace(MPV_URL_REGEX, "")
            ?.trim()
            ?.trimEnd('?', ':', '-')
            ?.takeIf { it.isNotEmpty() && !it.contains("://") }
        val specific = details.filter { !mpvDetailIsGeneric(it) }.distinctBy { it.lowercase() }
        val explanation = httpExplanation.trim()
        return buildString {
            if (explanation.isNotEmpty()) {
                append(explanation)
                append("\n\n")
            }
            append("HTTP $code")
            if (reason != null) append(" $reason")
            if (!file.isNullOrBlank()) append(" [$file]")
            specific.take(MPV_ERROR_LOG_LINES).forEach { line ->
                if (!file.isNullOrBlank() &&
                    (line.contains(file, ignoreCase = true) || file.contains(line, ignoreCase = true))
                ) {
                    return@forEach
                }
                append('\n')
                append(line)
            }
        }
    }
    val unique = details.distinctBy { it.lowercase() }
    val specific = unique.filter { !mpvDetailIsWrapper(it) }
    val chosen = (if (specific.isNotEmpty()) specific else unique.filter { !mpvDetailIsGeneric(it) }.ifEmpty { unique })
        .take(MPV_ERROR_LOG_LINES)
        .toMutableList()
    if (chosen.isEmpty()) return prependMpvStatusExplanation(statusExplanation, file ?: fallback, fallback)
    if (file != null && chosen.none { it.equals(file, ignoreCase = true) || it.contains(file, ignoreCase = true) }) {
        if (mpvFileErrorHeadsCause(file)) {
            chosen.add(0, file)
        } else {
            chosen[0] = "${chosen[0]} [$file]"
        }
    }
    return prependMpvStatusExplanation(statusExplanation, chosen.joinToString("\n"), fallback)
}

private fun mpvDisplayDetail(line: String): String? {
    if (MPV_HTTP_ERROR_REGEX.containsMatchIn(line)) return null
    if (line.contains("ytdl_hook", ignoreCase = true)) return null
    val stripped = line
        .replace(MPV_URL_REGEX, "")
        .replace(Regex("^\\[[^\\]]+]\\s*"), "")
        .replace(MPV_FFMPEG_ADDR_REGEX, "")
        .trim()
        .trimEnd('?', ':', '-')
        .trim()
    if (stripped.isEmpty() || stripped.contains("://")) return null
    return stripped
}

internal fun mpvWarnLineIsUseful(prefix: String, text: String): Boolean {
    if (prefix.contains("ytdl_hook", ignoreCase = true)) return false
    return mpvLogTextIsCause(text)
}

internal fun mpvLogTextIsCause(text: String): Boolean {
    return MPV_DIAGNOSTIC_LOG_REGEX.containsMatchIn(text) ||
        MPV_OUTPUT_CAUSE_REGEX.containsMatchIn(text) ||
        mpvInfoLineIsUseful(text)
}

internal fun mpvInfoLineIsUseful(text: String): Boolean {
    return MPV_INFO_CAUSE_REGEX.containsMatchIn(text.trim())
}

private fun mpvFpsLabel(raw: String?): String? {
    val value = raw?.toDoubleOrNull() ?: return null
    if (value < 1.0) return null
    val rounded = (value * 1000.0).toInt() / 1000.0
    val text = rounded.toString().trimEnd('0').trimEnd('.')
    return "${text}fps"
}

private fun mpvDetailIsGeneric(detail: String): Boolean {
    return detail.equals("Failed to open", ignoreCase = true) ||
        detail.equals("loading failed", ignoreCase = true)
}

private fun mpvDetailIsWrapper(detail: String): Boolean {
    if (mpvDetailIsGeneric(detail)) return true
    return detail.equals("Could not initialize video chain.", ignoreCase = true) ||
        detail.equals("Could not initialize video chain", ignoreCase = true) ||
        detail.equals("Video: no video", ignoreCase = true) ||
        detail.equals("Could not open/initialize audio device -> no sound.", ignoreCase = true) ||
        detail.equals("Could not open/initialize audio device -> no sound", ignoreCase = true)
}

private fun mpvFileErrorHeadsCause(fileError: String): Boolean {
    return fileError.equals("loading failed", ignoreCase = true) ||
        fileError.equals("audio output initialization failed", ignoreCase = true) ||
        fileError.equals("video output initialization failed", ignoreCase = true) ||
        fileError.equals("unrecognized file format", ignoreCase = true) ||
        fileError.equals("no audio or video data played", ignoreCase = true) ||
        fileError.equals("not supported", ignoreCase = true) ||
        fileError.equals("something happened", ignoreCase = true)
}

internal enum class MpvNonHttpExplanationKind {
    InvalidContent,
    UnsupportedFormat,
    OpenFailed
}

internal fun mpvNonHttpExplanationKind(fileError: String?, logLine: String?): MpvNonHttpExplanationKind {
    val file = fileError?.trim()?.lowercase().orEmpty()
    val log = logLine?.lowercase().orEmpty()
    if (file == "unrecognized file format" ||
        file == "no audio or video data played" ||
        log.contains("unrecognized file format")
    ) {
        return MpvNonHttpExplanationKind.InvalidContent
    }
    if (file == "audio output initialization failed" ||
        file == "video output initialization failed" ||
        file == "not supported" ||
        log.contains("codec") ||
        log.contains("decoder") ||
        log.contains("hwdec")
    ) {
        return MpvNonHttpExplanationKind.UnsupportedFormat
    }
    return MpvNonHttpExplanationKind.OpenFailed
}

internal fun mpvNonHttpExplanationText(
    context: android.content.Context,
    fileError: String?,
    logLine: String?
): String {
    val raw = when (mpvNonHttpExplanationKind(fileError, logLine)) {
        MpvNonHttpExplanationKind.InvalidContent ->
            context.getString(R.string.player_error_source_invalid_content, "")
        MpvNonHttpExplanationKind.UnsupportedFormat ->
            context.getString(R.string.player_error_unsupported_format, "")
        MpvNonHttpExplanationKind.OpenFailed ->
            context.getString(R.string.player_error_stream_unavailable)
    }
    return raw.trim().removeSuffix("[]").trim()
}

internal fun prependMpvStatusExplanation(statusExplanation: String, technical: String, fallback: String): String {
    val explanation = statusExplanation.trim()
    val detail = technical.trim()
    if (explanation.isEmpty()) return detail
    if (detail.isBlank() || detail.equals(fallback, ignoreCase = true)) return explanation
    return "$explanation\n\n$detail"
}

private fun mpvErrorCodeName(code: Int): String = when (code) {
    0 -> "MPV_ERROR_SUCCESS"
    1 -> "MPV_ERROR_EVENT_QUEUE_FULL"
    2 -> "MPV_ERROR_NOMEM"
    3 -> "MPV_ERROR_UNINITIALIZED"
    4 -> "MPV_ERROR_INVALID_PARAMETER"
    5 -> "MPV_ERROR_OPTION_NOT_FOUND"
    6 -> "MPV_ERROR_OPTION_FORMAT"
    7 -> "MPV_ERROR_OPTION_ERROR"
    8 -> "MPV_ERROR_PROPERTY_NOT_FOUND"
    9 -> "MPV_ERROR_PROPERTY_FORMAT"
    10 -> "MPV_ERROR_PROPERTY_UNAVAILABLE"
    11 -> "MPV_ERROR_PROPERTY_ERROR"
    12 -> "MPV_ERROR_COMMAND"
    MPV_ERROR_LOADING_FAILED -> "MPV_ERROR_LOADING_FAILED"
    MPV_ERROR_AO_INIT_FAILED -> "MPV_ERROR_AO_INIT_FAILED"
    MPV_ERROR_VO_INIT_FAILED -> "MPV_ERROR_VO_INIT_FAILED"
    MPV_ERROR_NOTHING_TO_PLAY -> "MPV_ERROR_NOTHING_TO_PLAY"
    MPV_ERROR_UNKNOWN_FORMAT -> "MPV_ERROR_UNKNOWN_FORMAT"
    18 -> "MPV_ERROR_UNSUPPORTED"
    19 -> "MPV_ERROR_NOT_IMPLEMENTED"
    MPV_ERROR_GENERIC -> "MPV_ERROR_GENERIC"
    else -> "MPV_ERROR_$code"
}
