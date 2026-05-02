@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.components.LoadingIndicator
import com.nuvio.tv.ui.theme.NuvioColors
import io.github.anilbeesetti.nextlib.mediainfo.Chapter
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

private const val MKV_CHAPTER_PROBE_TIMEOUT_MS = 20_000L
private val MKV_CHAPTER_EXTENSIONS = listOf(".mkv", ".mk3d", ".mka")
private val MKV_MIME_MARKERS = listOf("matroska", "video/mkv", "audio/mkv")
private const val MAX_REASONABLE_VIDEO_MS = 30L * 24L * 60L * 60L * 1000L
private const val MAX_REASONABLE_VIDEO_SECONDS = MAX_REASONABLE_VIDEO_MS / 1_000L
private const val MKV_CHAPTER_DURATION_TOLERANCE_MS = 5L * 60L * 1000L
private val GENERIC_MKV_CHAPTER_TITLE_REGEX = Regex(
    pattern = "^chapter\\s*[:#-]?\\s*\\d+$",
    option = RegexOption.IGNORE_CASE
)

data class MkvChapter(
    val index: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long?
)

private data class NextLibTimingPlan(
    val startTimesMs: List<Long>,
    val endTimesMs: List<Long?>,
    val durationMs: Long
)

private data class NextLibRawChapter(
    val index: Int,
    val title: String,
    val start: Long,
    val end: Long
)

private data class NextLibTimingCandidate(
    val name: String,
    val startTimesMs: List<Long>,
    val endTimesMs: List<Long?>,
    val score: Int
)

private data class NextLibTimeRescale(
    val name: String,
    val numerator: Long,
    val denominator: Long,
    val preference: Int
)

// FFmpeg stores AVChapter start/end in the chapter time_base; depending on the
// NextLib build those values can arrive pre-scaled or still in common FFmpeg/MKV units.
private val NEXTLIB_CHAPTER_TIME_RESCALES = listOf(
    NextLibTimeRescale(name = "milliseconds", numerator = 1L, denominator = 1L, preference = 48),
    NextLibTimeRescale(name = "ffmpeg-microseconds", numerator = 1L, denominator = 1_000L, preference = 44),
    NextLibTimeRescale(name = "matroska-nanoseconds", numerator = 1L, denominator = 1_000_000L, preference = 42),
    NextLibTimeRescale(name = "seconds", numerator = 1_000L, denominator = 1L, preference = 36)
)

private val NEXTLIB_DURATION_TIME_RESCALES = listOf(
    NextLibTimeRescale(name = "milliseconds", numerator = 1L, denominator = 1L, preference = 0),
    NextLibTimeRescale(name = "ffmpeg-microseconds", numerator = 1L, denominator = 1_000L, preference = 0),
    NextLibTimeRescale(name = "matroska-nanoseconds", numerator = 1L, denominator = 1_000_000L, preference = 0),
    NextLibTimeRescale(name = "seconds", numerator = 1_000L, denominator = 1L, preference = 0)
)

internal fun PlayerRuntimeController.applyMkvChapterSetting(
    enabled: Boolean,
    showCurrentChapterInControls: Boolean,
    hideGenericCurrentChapterInControls: Boolean
) {
    if (!enabled) {
        mkvChapterSupportEnabled = false
        mkvChapterProbeKey = null
        mkvChapterProbeJob?.cancel()
        _uiState.update {
            it.copy(
                mkvChapterSupportEnabled = false,
                showMkvChapterInControls = false,
                hideGenericMkvChapterInControls = false,
                mkvChapters = emptyList(),
                mkvChaptersLoading = false,
                showMkvChapterPanel = false
            )
        }
        return
    }

    val wasEnabled = mkvChapterSupportEnabled
    mkvChapterSupportEnabled = true
    _uiState.update {
        it.copy(
            mkvChapterSupportEnabled = true,
            showMkvChapterInControls = showCurrentChapterInControls,
            hideGenericMkvChapterInControls = hideGenericCurrentChapterInControls
        )
    }
    if (!wasEnabled) {
        loadMkvChaptersIfEnabled()
    }
}

internal fun PlayerRuntimeController.loadMkvChaptersIfEnabled(
    url: String = currentStreamUrl,
    headers: Map<String, String> = currentHeaders
) {
    if (!mkvChapterSupportEnabled) return
    val sourceUrl = url.takeIf { it.isNotBlank() } ?: return

    if (!isLikelyMkvChapterSource(sourceUrl)) {
        mkvChapterProbeJob?.cancel()
        mkvChapterProbeKey = null
        _uiState.update {
            it.copy(
                mkvChapters = emptyList(),
                mkvChaptersLoading = false,
                showMkvChapterPanel = false
            )
        }
        return
    }

    val probeKey = buildMkvChapterProbeKey(sourceUrl, headers)
    val state = _uiState.value
    if (probeKey == mkvChapterProbeKey && (state.mkvChapters.isNotEmpty() || state.mkvChaptersLoading)) {
        return
    }

    mkvChapterProbeKey = probeKey
    mkvChapterProbeJob?.cancel()
    _uiState.update {
        it.copy(
            mkvChapterSupportEnabled = true,
            mkvChapters = emptyList(),
            mkvChaptersLoading = true,
            showMkvChapterPanel = false
        )
    }

    val durationHintMs = currentPlaybackDurationMs().takeIf { it > 0 } ?: lastKnownDuration
    mkvChapterProbeJob = scope.launch {
        val chapters = withTimeoutOrNull(MKV_CHAPTER_PROBE_TIMEOUT_MS) {
            withContext(Dispatchers.IO) {
                probeMkvChaptersWithNextLib(
                    context = context,
                    sourceUrl = sourceUrl,
                    durationHintMs = durationHintMs
                )
            }
        }.orEmpty()

        if (!mkvChapterSupportEnabled || mkvChapterProbeKey != probeKey || currentStreamUrl != sourceUrl) {
            return@launch
        }

        _uiState.update {
            it.copy(
                mkvChapters = chapters,
                mkvChaptersLoading = false,
                showMkvChapterPanel = it.showMkvChapterPanel && chapters.isNotEmpty()
            )
        }
    }
}

internal fun PlayerRuntimeController.showMkvChapterPanel() {
    val state = _uiState.value
    if (!mkvChapterSupportEnabled || state.mkvChapters.isEmpty()) return
    _uiState.update {
        it.copy(
            showMkvChapterPanel = true,
            showControls = true,
            showAudioOverlay = false,
            showSubtitleOverlay = false,
            showSubtitleStylePanel = false,
            showSubtitleTimingDialog = false,
            showSubtitleDelayOverlay = false,
            showSpeedDialog = false,
            showMoreDialog = false,
            showEpisodesPanel = false,
            showEpisodeStreams = false,
            showSourcesPanel = false
        )
    }
}

internal fun PlayerRuntimeController.dismissMkvChapterPanel() {
    _uiState.update { it.copy(showMkvChapterPanel = false) }
    scheduleHideControls()
}

internal fun PlayerRuntimeController.selectMkvChapter(chapter: MkvChapter) {
    if (!mkvChapterSupportEnabled) return
    val duration = currentPlaybackDurationMs().takeIf { it > 0 } ?: Long.MAX_VALUE
    val target = chapter.startMs.coerceIn(0L, duration)

    pendingPreviewSeekPosition = null
    seekPlaybackTo(target)
    updatePlaybackTimeline(currentPosition = target)
    scheduleProgressSyncAfterSeek()
    _uiState.update {
        it.copy(
            showMkvChapterPanel = false,
            showControls = true
        )
    }
    scheduleHideControls()
}

private fun PlayerRuntimeController.isLikelyMkvChapterSource(sourceUrl: String): Boolean {
    val normalizedUrl = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
    val embeddedUrl = extractEmbeddedResolveUrl(sourceUrl)?.substringBefore('?')?.lowercase(Locale.ROOT)
    val filename = currentFilename?.lowercase(Locale.ROOT)
    val mime = currentStreamMimeType?.lowercase(Locale.ROOT)

    return MKV_CHAPTER_EXTENSIONS.any { extension ->
        normalizedUrl.endsWith(extension) ||
            embeddedUrl?.endsWith(extension) == true ||
            filename?.endsWith(extension) == true
    } || MKV_MIME_MARKERS.any { marker -> mime?.contains(marker) == true }
}

private fun PlayerRuntimeController.buildMkvChapterProbeKey(
    sourceUrl: String,
    headers: Map<String, String>
): String {
    return listOf(
        sourceUrl,
        currentFilename.orEmpty(),
        currentStreamMimeType.orEmpty(),
        headers.hashCode().toString()
    ).joinToString("|")
}

private fun probeMkvChaptersWithNextLib(
    context: Context,
    sourceUrl: String,
    durationHintMs: Long
): List<MkvChapter> {
    val candidates = buildMkvChapterProbeCandidates(sourceUrl)
    candidates.forEach { candidateUrl ->
        var mediaInfo: MediaInfo? = null
        try {
            mediaInfo = MediaInfoBuilder()
                .from(context = context, uri = Uri.parse(candidateUrl))
                .build() ?: return@forEach

            val rawChapters = mediaInfo.chapters.orEmpty()
                .mapIndexed { fallbackIndex, chapter ->
                    chapter.toNextLibRawChapter(fallbackIndex)
                }
            val timingPlan = resolveNextLibTimingPlan(mediaInfo, rawChapters, durationHintMs)
            val chapters = rawChapters
                .toMkvChapters(timingPlan)
                .mapIndexed { listIndex, chapter -> listIndex to chapter }
                .sortedWith(compareBy<Pair<Int, MkvChapter>> { it.second.startMs }.thenBy { it.first })
                .map { it.second }

            if (chapters.isNotEmpty()) {
                return fillMkvChapterEndTimes(
                    chapters,
                    timingPlan.durationMs.takeIf { it > 0 } ?: durationHintMs
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(PlayerRuntimeController.TAG, "MKV chapter probe failed: ${e.message}")
        } finally {
            runCatching { mediaInfo?.release() }
        }
    }
    return emptyList()
}

private fun resolveNextLibTimingPlan(
    mediaInfo: MediaInfo,
    chapters: List<NextLibRawChapter>,
    durationHintMs: Long
): NextLibTimingPlan {
    val durationMs = resolveNextLibDurationMs(mediaInfo.duration, durationHintMs)
    val bestCandidate = buildNextLibTimingCandidates(chapters, durationMs)
        .maxByOrNull { it.score }

    if (bestCandidate == null) {
        return NextLibTimingPlan(
            startTimesMs = emptyList(),
            endTimesMs = emptyList(),
            durationMs = durationMs
        )
    }

    if (bestCandidate.startTimesMs.all { it == 0L } && chapters.size > 1) {
        Log.w(PlayerRuntimeController.TAG, "NextLib returned MKV chapters without usable chapter timestamps")
    } else if (bestCandidate.name != "start-milliseconds") {
        Log.d(PlayerRuntimeController.TAG, "MKV chapter timing resolved as ${bestCandidate.name}")
    }

    return NextLibTimingPlan(
        startTimesMs = bestCandidate.startTimesMs,
        endTimesMs = bestCandidate.endTimesMs,
        durationMs = durationMs
    )
}

private fun resolveNextLibDurationMs(
    rawDuration: Long,
    durationHintMs: Long
): Long {
    val hintedDurationMs = durationHintMs.takeIf { it in 1..MAX_REASONABLE_VIDEO_MS }
    if (rawDuration <= 0L) return hintedDurationMs ?: 0L

    val durationCandidates = NEXTLIB_DURATION_TIME_RESCALES
        .mapNotNull { rescale ->
            rawDuration
                .scaleNextLibTime(rescale)
                .takeIf { it in 1..MAX_REASONABLE_VIDEO_MS }
        }
        .distinct()

    if (hintedDurationMs == null) {
        return durationCandidates.firstOrNull { it >= TimeUnit.SECONDS.toMillis(10L) }
            ?: durationCandidates.firstOrNull()
            ?: 0L
    }

    return durationCandidates
        .minByOrNull { duration -> absoluteDifference(duration, hintedDurationMs) }
        ?: hintedDurationMs
}

private fun buildNextLibTimingCandidates(
    chapters: List<NextLibRawChapter>,
    durationMs: Long
): List<NextLibTimingCandidate> {
    if (chapters.isEmpty()) return emptyList()

    return NEXTLIB_CHAPTER_TIME_RESCALES.flatMap { rescale ->
        listOf(
            buildStartFieldCandidate(chapters, durationMs, rescale),
            buildPreviousEndFieldCandidate(chapters, durationMs, rescale),
            buildEndFieldCandidate(chapters, durationMs, rescale)
        )
    }
}

private fun buildStartFieldCandidate(
    chapters: List<NextLibRawChapter>,
    durationMs: Long,
    rescale: NextLibTimeRescale
): NextLibTimingCandidate {
    val startTimesMs = chapters.map { chapter -> chapter.start.scaleNextLibTime(rescale) }
    val endTimesMs = chapters.mapIndexed { index, chapter ->
        chapter.end
            .scaleNextLibTime(rescale)
            .takeIf { endMs -> endMs > startTimesMs[index] && endMs in 0..MAX_REASONABLE_VIDEO_MS }
    }

    return NextLibTimingCandidate(
        name = "start-${rescale.name}",
        startTimesMs = startTimesMs,
        endTimesMs = endTimesMs,
        score = scoreNextLibTimingCandidate(
            startTimesMs = startTimesMs,
            durationMs = durationMs,
            sourcePreference = rescale.preference + 30
        )
    )
}

private fun buildPreviousEndFieldCandidate(
    chapters: List<NextLibRawChapter>,
    durationMs: Long,
    rescale: NextLibTimeRescale
): NextLibTimingCandidate {
    val startTimesMs = chapters.indices.map { index ->
        if (index == 0) {
            0L
        } else {
            chapters[index - 1].end.scaleNextLibTime(rescale)
        }
    }
    val endTimesMs = chapters.mapIndexed { index, chapter ->
        chapter.end
            .scaleNextLibTime(rescale)
            .takeIf { endMs -> endMs > startTimesMs[index] && endMs in 0..MAX_REASONABLE_VIDEO_MS }
    }

    return NextLibTimingCandidate(
        name = "previous-end-${rescale.name}",
        startTimesMs = startTimesMs,
        endTimesMs = endTimesMs,
        score = scoreNextLibTimingCandidate(
            startTimesMs = startTimesMs,
            durationMs = durationMs,
            sourcePreference = rescale.preference + 12
        )
    )
}

private fun buildEndFieldCandidate(
    chapters: List<NextLibRawChapter>,
    durationMs: Long,
    rescale: NextLibTimeRescale
): NextLibTimingCandidate {
    val startTimesMs = chapters.map { chapter -> chapter.end.scaleNextLibTime(rescale) }

    return NextLibTimingCandidate(
        name = "end-${rescale.name}",
        startTimesMs = startTimesMs,
        endTimesMs = emptyList(),
        score = scoreNextLibTimingCandidate(
            startTimesMs = startTimesMs,
            durationMs = durationMs,
            sourcePreference = rescale.preference
        )
    )
}

private fun scoreNextLibTimingCandidate(
    startTimesMs: List<Long>,
    durationMs: Long,
    sourcePreference: Int
): Int {
    if (startTimesMs.isEmpty()) return Int.MIN_VALUE
    if (startTimesMs.any { it < 0L || it > MAX_REASONABLE_VIDEO_MS }) return Int.MIN_VALUE / 2

    val adjacentPairs = startTimesMs.zipWithNext()
    val decreasingPairs = adjacentPairs.count { (current, next) -> next < current }
    val distinctDisplayedSecondCount = startTimesMs
        .map { timeMs -> TimeUnit.MILLISECONDS.toSeconds(timeMs) }
        .distinct()
        .size
    val maxStartMs = startTimesMs.maxOrNull() ?: 0L

    var score = sourcePreference
    score += startTimesMs.count { it > 0L } * 4
    score += startTimesMs.distinct().size * 3
    score += distinctDisplayedSecondCount * 14
    score += adjacentPairs.count { (current, next) -> next >= current } * 5
    score += adjacentPairs.count { (current, next) -> next > current } * 6
    score -= decreasingPairs * 1_000

    score += when (startTimesMs.firstOrNull() ?: 0L) {
        in 0L..1_000L -> 80
        in 1_001L..60_000L -> 15
        else -> -120
    }

    if (startTimesMs.size > 1 && distinctDisplayedSecondCount <= 1) {
        score -= 1_200
    }
    if (startTimesMs.size > 1 && maxStartMs <= 0L) {
        score -= 1_200
    }

    val effectiveDurationMs = durationMs.takeIf { it in 1..MAX_REASONABLE_VIDEO_MS }
    if (effectiveDurationMs != null) {
        val toleranceMs = chapterDurationToleranceMs(effectiveDurationMs)
        if (maxStartMs <= effectiveDurationMs + toleranceMs) {
            score += 120
        } else {
            score -= 800
        }

        val tooCompressedThresholdMs = (effectiveDurationMs / 100L).coerceAtLeast(1L)
        if (maxStartMs in 1 until tooCompressedThresholdMs) {
            score -= 300
        }
    }

    return score
}

private fun Chapter.toNextLibRawChapter(fallbackIndex: Int): NextLibRawChapter {
    val (rawStart, rawEnd) = normalizeNextLibChapterTimes(
        start = start,
        end = end
    )

    return NextLibRawChapter(
        index = index.takeIf { it >= 0 } ?: fallbackIndex,
        title = title?.takeIf { it.isNotBlank() } ?: "Chapter ${fallbackIndex + 1}",
        start = rawStart,
        end = rawEnd
    )
}

private fun normalizeNextLibChapterTimes(start: Long, end: Long): Pair<Long, Long> {
    val directStart = start.sanitizeNextLibChapterTime()
    val directEnd = end.sanitizeNextLibChapterTime()

    unpackPackedNextLibJniTimes(start)?.let { packed ->
        if (!directNextLibTimesAreUsable(directStart, directEnd)) {
            return packed
        }
    }

    return directStart to directEnd
}

private fun Long.sanitizeNextLibChapterTime(): Long {
    return takeUnless { it == Long.MIN_VALUE }
        ?.coerceAtLeast(0L)
        ?: 0L
}

private fun directNextLibTimesAreUsable(start: Long, end: Long): Boolean {
    if (start in 0..MAX_REASONABLE_VIDEO_MS && end in 0..MAX_REASONABLE_VIDEO_MS) {
        return end == 0L || end >= start
    }
    return false
}

private fun unpackPackedNextLibJniTimes(value: Long): Pair<Long, Long>? {
    if (value in 0..MAX_REASONABLE_VIDEO_MS) return null

    // Some 32-bit NextLib builds pass C++ long values to a JNI jlong callback.
    // The first Kotlin Long then contains start_ms in the low word and end_ms in the high word.
    val packedStartMs = value and 0xFFFF_FFFFL
    val packedEndMs = value ushr 32
    if (packedStartMs !in 0..MAX_REASONABLE_VIDEO_MS) return null
    if (packedEndMs !in 0..MAX_REASONABLE_VIDEO_MS) return null
    if (packedEndMs > 0L && packedEndMs < packedStartMs) return null

    return packedStartMs to packedEndMs
}

private fun List<NextLibRawChapter>.toMkvChapters(timingPlan: NextLibTimingPlan): List<MkvChapter> {
    return mapIndexed { listIndex, chapter ->
        val startMs = timingPlan.startTimesMs
            .getOrNull(listIndex)
            ?.takeIf { it in 0..MAX_REASONABLE_VIDEO_MS }
            ?: 0L
        val endMs = timingPlan.endTimesMs
            .getOrNull(listIndex)
            ?.takeIf { it > startMs && it in 0..MAX_REASONABLE_VIDEO_MS }

        MkvChapter(
            index = chapter.index,
            title = chapter.title,
            startMs = startMs,
            endMs = endMs
        )
    }
}

private fun Long.scaleNextLibTime(rescale: NextLibTimeRescale): Long {
    return scaleNextLibTime(
        numerator = rescale.numerator,
        denominator = rescale.denominator
    )
}

private fun Long.scaleNextLibTime(numerator: Long, denominator: Long): Long {
    if (this <= 0L) return 0L
    if (denominator <= 0L) return Long.MAX_VALUE

    return runCatching {
        Math.multiplyExact(this, numerator) / denominator
    }.getOrElse {
        ((this.toDouble() * numerator.toDouble()) / denominator.toDouble()).toLong()
    }.coerceAtLeast(0L)
}

private fun absoluteDifference(first: Long, second: Long): Long {
    return if (first >= second) first - second else second - first
}

private fun fillMkvChapterEndTimes(
    chapters: List<MkvChapter>,
    durationHintMs: Long
): List<MkvChapter> {
    return chapters.mapIndexed { index, chapter ->
        val inferredEnd = chapters.getOrNull(index + 1)?.startMs
            ?: durationHintMs.takeIf { it > chapter.startMs }
        if (chapter.endMs != null) {
            chapter
        } else {
            chapter.copy(endMs = inferredEnd)
        }
    }
}

private fun chapterDurationToleranceMs(durationMs: Long): Long {
    if (durationMs <= 0L) return MKV_CHAPTER_DURATION_TOLERANCE_MS
    return maxOf(MKV_CHAPTER_DURATION_TOLERANCE_MS, durationMs / 100L)
}

private fun buildMkvChapterProbeCandidates(sourceUrl: String): List<String> {
    val embeddedUrl = extractEmbeddedResolveUrl(sourceUrl)
    val preferEmbedded = isResolveProxyUrl(sourceUrl)
    return buildList {
        if (preferEmbedded && !embeddedUrl.isNullOrBlank() && embeddedUrl != sourceUrl) {
            add(embeddedUrl)
        }
        add(sourceUrl)
        if (!preferEmbedded && !embeddedUrl.isNullOrBlank() && embeddedUrl != sourceUrl) {
            add(embeddedUrl)
        }
    }.distinct()
}

private fun isResolveProxyUrl(sourceUrl: String): Boolean {
    val normalized = sourceUrl.substringBefore('?').lowercase(Locale.ROOT)
    return "/resolve/" in normalized
}

private fun extractEmbeddedResolveUrl(sourceUrl: String): String? {
    val marker = "/resolve/"
    val markerIndex = sourceUrl.indexOf(marker, ignoreCase = true)
    if (markerIndex < 0) return null

    val afterResolve = sourceUrl.substring(markerIndex + marker.length)
    val nestedEncoded = afterResolve.substringAfter('/', missingDelimiterValue = "")
        .substringAfter('/', missingDelimiterValue = "")
    if (nestedEncoded.isBlank()) return null

    return runCatching {
        URLDecoder.decode(nestedEncoded, StandardCharsets.UTF_8.name())
    }.getOrNull()
}

@Composable
internal fun MkvChapterPanelHost(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    modifier: Modifier = Modifier
) {
    val visible = uiState.showMkvChapterPanel && uiState.error == null
    if (!visible) return

    val playbackTimeline by viewModel.playbackTimeline.collectAsState()
    MkvChapterSelectionOverlay(
        visible = true,
        chapters = uiState.mkvChapters,
        currentPositionMs = playbackTimeline.currentPosition,
        isLoading = uiState.mkvChaptersLoading,
        onChapterSelected = { viewModel.onEvent(PlayerEvent.OnSelectMkvChapter(it)) },
        onDismiss = { viewModel.onEvent(PlayerEvent.OnDismissMkvChapterPanel) },
        modifier = modifier
    )
}

@Composable
private fun MkvChapterSelectionOverlay(
    visible: Boolean,
    chapters: List<MkvChapter>,
    currentPositionMs: Long,
    isLoading: Boolean,
    onChapterSelected: (MkvChapter) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val focusRequester = remember { FocusRequester() }
    val activeIndex = activeMkvChapterIndex(chapters, currentPositionMs).coerceAtLeast(0)

    LaunchedEffect(visible, chapters, activeIndex) {
        if (!visible || chapters.isEmpty()) return@LaunchedEffect
        listState.scrollToItem(activeIndex.coerceAtMost(chapters.lastIndex))
        delay(120)
        runCatching { focusRequester.requestFocus() }
    }

    PlayerOverlayScaffold(
        visible = visible,
        onDismiss = onDismiss,
        modifier = modifier,
        captureKeys = false,
        contentPadding = PaddingValues(start = 52.dp, end = 52.dp, top = 36.dp, bottom = 88.dp)
    ) {
        Column(
            modifier = Modifier
                .width(560.dp)
                .align(Alignment.BottomStart)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Bottom
        ) {
            Text(
                text = stringResource(R.string.player_chapters_title),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 4.dp)
            )
            if (chapters.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.player_chapters_count, chapters.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.72f),
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            when {
                isLoading -> {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 180.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                }

                chapters.isEmpty() -> {
                    Text(
                        text = stringResource(R.string.player_chapters_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.padding(top = 8.dp, bottom = 12.dp)
                    )
                }

                else -> {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        contentPadding = PaddingValues(top = 8.dp, bottom = 8.dp),
                        modifier = Modifier
                            .heightIn(min = 120.dp, max = 620.dp)
                            .fillMaxWidth()
                    ) {
                        itemsIndexed(
                            items = chapters,
                            key = { listIndex, chapter -> "$listIndex:${chapter.index}:${chapter.startMs}" }
                        ) { listIndex, chapter ->
                            MkvChapterCard(
                                chapter = chapter,
                                isActive = listIndex == activeIndex,
                                focusRequester = if (listIndex == activeIndex) {
                                    focusRequester
                                } else {
                                    null
                                },
                                onClick = { onChapterSelected(chapter) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MkvChapterCard(
    chapter: MkvChapter,
    isActive: Boolean,
    focusRequester: FocusRequester?,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = if (isActive) NuvioColors.Secondary else Color.Transparent,
            focusedContainerColor = if (isActive) NuvioColors.Secondary else Color.Transparent
        ),
        shape = CardDefaults.shape(RoundedCornerShape(12.dp)),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = RoundedCornerShape(12.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1f, pressedScale = 1f)
    ) {
        val primaryTextColor = if (isActive) NuvioColors.OnSecondary else Color.White
        val secondaryTextColor = if (isActive) {
            NuvioColors.OnSecondary.copy(alpha = 0.82f)
        } else {
            Color.White.copy(alpha = 0.62f)
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = chapter.title,
                    style = MaterialTheme.typography.bodyLarge,
                    color = primaryTextColor,
                    fontWeight = if (isFocused || isActive) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = formatMkvChapterTime(chapter.startMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = secondaryTextColor,
                    maxLines = 1
                )
            }

            if (isActive) {
                Spacer(modifier = Modifier.width(12.dp))
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = null,
                    tint = primaryTextColor,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

internal fun currentMkvChapter(
    chapters: List<MkvChapter>,
    positionMs: Long,
    hideGenericTitle: Boolean = false
): MkvChapter? {
    val index = activeMkvChapterIndex(chapters, positionMs)
    val chapter = chapters.getOrNull(index)
    if (hideGenericTitle && chapter != null && isGenericMkvChapterTitle(chapter.title)) {
        return null
    }
    return chapter
}

private fun isGenericMkvChapterTitle(title: String): Boolean {
    val trimmed = title.trim()
    if (trimmed.isBlank()) return true
    if (GENERIC_MKV_CHAPTER_TITLE_REGEX.matches(trimmed)) return true
    return trimmed.none { it.isLetter() }
}

private fun activeMkvChapterIndex(chapters: List<MkvChapter>, positionMs: Long): Int {
    if (chapters.isEmpty()) return -1

    var low = 0
    var high = chapters.lastIndex
    var candidate = -1
    while (low <= high) {
        val mid = (low + high).ushr(1)
        if (chapters[mid].startMs <= positionMs) {
            candidate = mid
            low = mid + 1
        } else {
            high = mid - 1
        }
    }

    val chapter = chapters.getOrNull(candidate) ?: return -1
    return if (chapter.endMs == null || positionMs < chapter.endMs) {
        candidate
    } else {
        -1
    }
}

internal fun formatMkvChapterTime(positionMs: Long): String {
    val totalSeconds = TimeUnit.MILLISECONDS.toSeconds(positionMs.coerceAtLeast(0L))
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    return if (hours > 0) {
        String.format(Locale.ROOT, "%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format(Locale.ROOT, "%d:%02d", minutes, seconds)
    }
}
