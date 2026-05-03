package com.nuvio.tv.ui.screens.player

import android.util.Log
import io.framescout.SeekPreviewCacheKey
import io.framescout.SeekPreviewGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val SEEK_PREVIEW_LOG_TAG = "SeekPreview"
private val NF_TAG_REGEX = Regex("""[.\-]NF[.\-]""")

/**
 * Mirrors the persisted "seek preview enabled" flag into the controller.
 * Called once from [PlayerRuntimeController]'s init block.
 */
internal fun PlayerRuntimeController.observeSeekPreviewSettings() {
    scope.launch {
        playerSettingsDataStore.playerSettings.collectLatest { settings ->
            val changed = seekPreviewEnabled != settings.seekPreviewEnabled ||
                seekPreviewGenerationType != settings.seekPreviewGenerationType
            seekPreviewEnabled = settings.seekPreviewEnabled
            seekPreviewGenerationType = settings.seekPreviewGenerationType
            seekPreviewCacheBudgetBytes = settings.seekPreviewCacheLimitMb.toLong() * 1024L * 1024L
            if (changed) {
                Log.i(SEEK_PREVIEW_LOG_TAG, "setting → enabled=${settings.seekPreviewEnabled} type=${settings.seekPreviewGenerationType}")
                if (settings.seekPreviewEnabled) {
                    // Pre-fetch streams so pickSeekPreviewSource has candidates
                    // ready when generation starts. No-op on cache hit.
                    loadSourceStreams(forceRefresh = false)
                }
            }
        }
    }
}

/**
 * Retries [startSeekPreviewIfReady] when the source stream list first
 * becomes non-empty. Handles the race where streams load after the
 * player opens and duration is already known.
 */
internal fun PlayerRuntimeController.observeSourceStreamsForSeekPreview() {
    scope.launch {
        _uiState
            .map { (it.sourceAllStreams + it.episodeAllStreams).isNotEmpty() }
            .distinctUntilChanged()
            .collect { nonEmpty ->
                if (!nonEmpty) return@collect
                // Streams just became available — retry start in case it was
                // deferred waiting for an MP4 source.
                if (!seekPreviewStartedForCurrentStream) {
                    val duration = _playbackTimeline.value.duration
                    if (duration > 0L) startSeekPreviewIfReady(duration)
                }
            }
    }
}

internal fun PlayerRuntimeController.observeSeekPreviewGeneratorState() {
    seekPreviewStateObserverJob?.cancel()
    seekPreviewStateObserverJob = scope.launch {
        var lastLoggedFraction = -1
        seekPreviewGenerator.state.collect { state ->
            when (state) {
                is SeekPreviewGenerator.State.Probing,
                is SeekPreviewGenerator.State.Done,
                is SeekPreviewGenerator.State.Unsupported,
                is SeekPreviewGenerator.State.Failed,
                is SeekPreviewGenerator.State.BadSource,
                is SeekPreviewGenerator.State.ChunkDone -> {
                    Log.i(SEEK_PREVIEW_LOG_TAG, "state → $state")
                    lastLoggedFraction = -1
                }
                is SeekPreviewGenerator.State.Generating -> {
                    val frac = if (state.framesTotal > 0) {
                        (state.framesDone * 10 / state.framesTotal)
                    } else 0
                    if (frac != lastLoggedFraction) {
                        lastLoggedFraction = frac
                        Log.i(
                            SEEK_PREVIEW_LOG_TAG,
                            "generating chunk=${state.chunkIndex + 1}/${state.totalChunks} " +
                                "${state.framesDone}/${state.framesTotal}"
                        )
                    }
                }
                SeekPreviewGenerator.State.Idle -> Unit
            }

            if (state is SeekPreviewGenerator.State.BadSource) {
                seekPreviewTriedSourceUrls.add(state.triedUrl)
                seekPreviewStartedForCurrentStream = false
                val duration = _playbackTimeline.value.duration
                if (duration > 0L) startSeekPreviewIfReady(duration)
                return@collect
            }

            if (state !is SeekPreviewGenerator.State.ChunkDone || !state.hasMoreChunks) return@collect
            seekPreviewGenerator.continueNextChunk(scope = scope)
        }
    }
}

/**
 * Kicks off generation for the current stream once duration is known.
 * No-op when disabled, duration unknown, or source is unsupported by
 * MMR (HLS/DASH/torrent stream).
 */
internal fun PlayerRuntimeController.startSeekPreviewIfReady(durationMs: Long) {
    if (!seekPreviewEnabled) return
    if (seekPreviewStartedForCurrentStream) return
    if (durationMs <= 0L) return
    if (durationMs < MIN_DURATION_FOR_THUMBNAILS_MS) {
        Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: duration ${formatDuration(durationMs)} is under ${MIN_DURATION_FOR_THUMBNAILS_MS / 60_000} min threshold")
        seekPreviewStartedForCurrentStream = true
        return
    }
    if (isTorrentStream) {
        seekPreviewStartedForCurrentStream = true
        return
    }
    val url = currentStreamUrl.takeIf { it.isNotBlank() } ?: return

    Log.i(SEEK_PREVIEW_LOG_TAG, "playing: duration=${formatDuration(durationMs)}  size=${formatSize(currentVideoSize)}\n  url: $url")

    // If the current stream is already an MP4 with a supported codec, use it directly — no probing needed.
    val candidates: List<SeekPreviewSource>
    val urlPath = url.substringBefore('?').lowercase()
    val currentName = currentFilename.orEmpty().ifBlank { urlPath.substringAfterLast('/') }
    if (urlPath.endsWith(".mp4") && !isHEVC(currentName) && url !in seekPreviewTriedSourceUrls) {
        Log.i(SEEK_PREVIEW_LOG_TAG, "current stream is MP4 — using it directly for thumbnail generation")
        candidates = listOf(SeekPreviewSource(url = url, headers = currentHeaders, qualityValue = -1, videoSize = currentVideoSize))
    } else {
        val ranked = pickTopSeekPreviewSources(url, excluding = seekPreviewTriedSourceUrls)
        if (ranked.isEmpty()) {
            val allStreams = _uiState.value.sourceAllStreams + _uiState.value.episodeAllStreams
            if (allStreams.isEmpty()) return  // defer to observeSourceStreamsForSeekPreview
            val excludedNote = if (seekPreviewTriedSourceUrls.isNotEmpty()) " (${seekPreviewTriedSourceUrls.size} source(s) excluded as bad)" else ""
            Log.i(SEEK_PREVIEW_LOG_TAG, "start skipped: no MP4 source in ${allStreams.size} streams$excludedNote")
            seekPreviewStartedForCurrentStream = true
            return
        }
        Log.i(SEEK_PREVIEW_LOG_TAG, "${ranked.size} MP4 candidate(s): ${ranked.joinToString { "${it.qualityValue}p" }}")
        candidates = ranked
    }

    val key = SeekPreviewCacheKey.compute(
        SeekPreviewCacheKey.Input(
            videoHash = currentVideoHash,
            filename = currentFilename,
            videoSize = currentVideoSize,
            infoHash = currentInfoHash,
            fileIdx = currentFileIdx,
            url = url
        )
    )
    seekPreviewStartedForCurrentStream = true

    val capturedCandidates = candidates
    seekPreviewProbeJob?.cancel()
    seekPreviewProbeJob = scope.launch(Dispatchers.IO) {
        runCatching { seekPreviewStore.trimLru(seekPreviewCacheBudgetBytes) }

        // Current-stream fallback has duration = target by definition, skip probing.
        val source = if (capturedCandidates.size == 1 && capturedCandidates.first().url == url) {
            capturedCandidates.first()
        } else {
            val best = selectBestByDuration(capturedCandidates, durationMs)
            if (best == null) {
                Log.i(SEEK_PREVIEW_LOG_TAG, "no MP4 source within ${ACCEPTABLE_DURATION_DELTA_MS / 1000}s of playing duration — skipping thumbnail generation")
                return@launch
            }
            best
        }

        val sourceLabel = if (source.url == url) "main stream" else "${source.qualityValue}p alt"
        Log.i(
            SEEK_PREVIEW_LOG_TAG,
            "generating thumbnails from $sourceLabel  size=${formatSize(source.videoSize)}\n  url: ${source.url}"
        )

        seekPreviewGenerator.start(
            input = SeekPreviewGenerator.Input(
                key = key,
                url = source.url,
                headers = source.headers,
                durationMs = durationMs,
                mimeTypeHint = null,
                generationType = seekPreviewGenerationType
            ),
            scope = scope
        )
    }
}

private fun isHEVC(name: String): Boolean {
    val s = name.lowercase()
    return s.contains("x265") || s.contains("hevc") || s.contains("h265") || s.contains("h.265")
}

private fun formatDuration(ms: Long): String {
    val h = ms / 3_600_000
    val m = (ms % 3_600_000) / 60_000
    val s = (ms % 60_000) / 1_000
    return if (h > 0) "%dh %02dm %02ds".format(h, m, s) else "%dm %02ds".format(m, s)
}

private fun formatSize(bytes: Long?) = bytes?.let { "%.2f GB".format(it / 1_073_741_824.0) } ?: "unknown"

private data class SeekPreviewSource(
    val url: String,
    val headers: Map<String, String>,
    val qualityValue: Int,
    val videoSize: Long? = null
)

// Parses quality from any name string (filename or URL path segment).
private fun qualityFromName(name: String): Int {
    val s = name.lowercase()
    return when {
        s.contains("2160p") || s.contains("4k") || s.contains("uhd") -> 2160
        s.contains("1080p") || s.contains("1080i") -> 1080
        s.contains("720p") -> 720
        s.contains("480p") -> 480
        s.contains("360p") -> 360
        else -> -1
    }
}

private enum class SourceType { WEB_DL, WEBRIP, BLURAY, OTHER }

private fun detectSourceType(name: String): SourceType {
    val s = name.lowercase()
    return when {
        s.contains("bluray") || s.contains("blu-ray") || s.contains("bdrip") || s.contains("brrip") -> SourceType.BLURAY
        s.contains("web-dl") || s.contains("webdl") || s.contains("web.dl") -> SourceType.WEB_DL
        s.contains("webrip") || s.contains("hdrip") || s.contains("hdtv") -> SourceType.WEBRIP
        else -> SourceType.OTHER
    }
}

// Known streaming platform tags found in release filenames.
private fun extractPlatformTag(name: String): String? {
    val s = name.uppercase()
    return when {
        s.contains("AMZN") || s.contains("AMAZON") -> "AMZN"
        s.contains("NFLX") || s.contains("NETFLIX") || NF_TAG_REGEX.containsMatchIn(s) -> "NF"
        s.contains("DSNP") || s.contains("DISNEY") -> "DSNP"
        s.contains("ATVP") || s.contains("APPLETV") -> "ATVP"
        s.contains("HMAX") || s.contains("HBOM") -> "HMAX"
        s.contains("PCOK") || s.contains("PEACOCK") -> "PCOK"
        s.contains("HULU") -> "HULU"
        s.contains("PMTP") || s.contains("PARAMOUNT") -> "PMTP"
        else -> null
    }
}

// Extracts the release group tag from a filename (the token after the last '-').
// Returns null if no valid group tag is found.
private fun extractReleaseGroup(name: String): String? {
    val base = name.substringBeforeLast('.').trim()
    val lastDash = base.lastIndexOf('-')
    if (lastDash < 0) return null
    val group = base.substring(lastDash + 1).trim()
    return if (group.length in 2..15 && group.all { it.isLetterOrDigit() }) group.uppercase() else null
}

// Lower score = more preferred.
// Priority: same release group → same type + platform → same type → same platform → anything.
// Quality is not part of the primary score — lower quality is preferred via the secondary sort.
private fun sourceScore(sameGroup: Boolean, sameType: Boolean, samePlatform: Boolean): Int = when {
    sameGroup && sameType && samePlatform -> 0
    sameGroup && sameType -> 1
    sameGroup && samePlatform -> 2
    sameGroup -> 3
    sameType && samePlatform -> 4
    sameType -> 5
    samePlatform -> 6
    else -> 7
}

// Returns up to 10 MP4 alternative sources ranked by preference (best score first).
// Same release group/encode as the currently playing file is always preferred.
// Does not include the current playback URL — callers handle that fallback separately.
private fun PlayerRuntimeController.pickTopSeekPreviewSources(
    currentUrl: String,
    excluding: Set<String> = emptySet()
): List<SeekPreviewSource> {
    val allStreams = _uiState.value.sourceAllStreams + _uiState.value.episodeAllStreams

    val currentName = currentFilename.orEmpty().ifBlank { currentUrl.substringBefore('?').substringAfterLast('/') }
    val currentGroup = extractReleaseGroup(currentName)
    val currentType = detectSourceType(currentName)
    val currentPlatform = extractPlatformTag(currentName)
    Log.i(SEEK_PREVIEW_LOG_TAG, "source profile: type=$currentType  platform=${currentPlatform ?: "unknown"}  group=${currentGroup ?: "unknown"}")

    fun streamName(stream: com.nuvio.tv.domain.model.Stream, url: String): String =
        stream.behaviorHints?.filename
            ?: url.substringBefore('?').substringAfterLast('/')

    fun effectiveQuality(stream: com.nuvio.tv.domain.model.Stream, url: String): Int =
        stream.qualityValue.takeIf { it > 0 } ?: qualityFromName(streamName(stream, url))

    fun isSupportedFormat(stream: com.nuvio.tv.domain.model.Stream, url: String): Boolean {
        val path = url.substringBefore('?').lowercase()
        val filename = stream.behaviorHints?.filename?.lowercase().orEmpty()
        if (!path.endsWith(".mp4") && !filename.endsWith(".mp4")) return false
        val name = filename.ifBlank { path.substringAfterLast('/') }
        return !isHEVC(name)
    }

    fun isSameGroup(stream: com.nuvio.tv.domain.model.Stream, url: String): Boolean =
        currentGroup != null && extractReleaseGroup(streamName(stream, url)) == currentGroup

    fun isSameType(stream: com.nuvio.tv.domain.model.Stream, url: String): Boolean =
        currentType != SourceType.OTHER && detectSourceType(streamName(stream, url)) == currentType

    fun isSamePlatform(stream: com.nuvio.tv.domain.model.Stream, url: String): Boolean =
        currentPlatform != null && extractPlatformTag(streamName(stream, url)) == currentPlatform

    val ranked = allStreams
        .mapNotNull { stream ->
            val url = stream.getStreamUrl() ?: return@mapNotNull null
            val q = effectiveQuality(stream, url)
            if (url == currentUrl || url in excluding || !isSupportedFormat(stream, url) || q <= 0 || q > 1080 || stream.isTorrent())
                return@mapNotNull null
            stream to url
        }
        .sortedWith(compareBy(
            { (s, u) -> sourceScore(isSameGroup(s, u), isSameType(s, u), isSamePlatform(s, u)) },
            { (s, u) -> effectiveQuality(s, u) }
        ))
        .distinctBy { (s, u) -> streamName(s, u).lowercase() }
        .take(10)

    return ranked.map { (stream, url) ->
        SeekPreviewSource(
            url = url,
            headers = stream.behaviorHints?.proxyHeaders?.request.orEmpty(),
            qualityValue = effectiveQuality(stream, url),
            videoSize = stream.behaviorHints?.videoSize
        )
    }
}

// Probes candidates in ranked order and returns the first whose duration is within
// [ACCEPTABLE_DURATION_DELTA_MS] of [targetDurationMs]. Returns null if none qualify.
private suspend fun PlayerRuntimeController.selectBestByDuration(
    candidates: List<SeekPreviewSource>,
    targetDurationMs: Long
): SeekPreviewSource? {
    Log.i(SEEK_PREVIEW_LOG_TAG, "probing ${candidates.size} candidate(s) for duration match (target: ${formatDuration(targetDurationMs)}, threshold: ±${ACCEPTABLE_DURATION_DELTA_MS / 1000}s):")
    for (source in candidates) {
        if (!currentCoroutineContext().isActive) break
        Log.i(SEEK_PREVIEW_LOG_TAG, "  checking ${source.qualityValue}p: ${source.url}")
        val duration = seekPreviewGenerator.probeDurationMs(source.url, source.headers)
        if (duration == null) {
            Log.i(SEEK_PREVIEW_LOG_TAG, "  → probe failed, skipping")
            continue
        }
        val deltaMs = kotlin.math.abs(duration - targetDurationMs)
        val deltaS = (duration - targetDurationMs) / 1000
        Log.i(SEEK_PREVIEW_LOG_TAG, "  → ${formatDuration(duration)} (Δ${if (deltaS >= 0) "+" else ""}${deltaS}s)")
        if (deltaMs <= ACCEPTABLE_DURATION_DELTA_MS) {
            Log.i(SEEK_PREVIEW_LOG_TAG, "  within threshold — selected")
            return source
        }
        Log.i(SEEK_PREVIEW_LOG_TAG, "  delta too large — trying next")
    }
    return null
}

private const val ACCEPTABLE_DURATION_DELTA_MS = 3_000L
private const val MIN_DURATION_FOR_THUMBNAILS_MS = 3 * 60_000L

/**
 * Called when the current stream is being torn down (release or switch).
 * Resets per-stream state so the next stream can kick off its own run.
 */
internal fun PlayerRuntimeController.resetSeekPreviewForNewStream() {
    seekPreviewProbeJob?.cancel()
    seekPreviewProbeJob = null
    seekPreviewStartedForCurrentStream = false
    seekPreviewTriedSourceUrls.clear()
    seekPreviewGenerator.stop()
}

internal fun PlayerRuntimeController.releaseSeekPreview() {
    resetSeekPreviewForNewStream()
    seekPreviewStateObserverJob?.cancel()
    seekPreviewStateObserverJob = null
}

/** UI-thread–safe lookup for the overlay. */
internal fun PlayerRuntimeController.nearestSeekPreviewJpeg(tsMs: Long): ByteArray? =
    seekPreviewGenerator.nearestJpeg(tsMs)
