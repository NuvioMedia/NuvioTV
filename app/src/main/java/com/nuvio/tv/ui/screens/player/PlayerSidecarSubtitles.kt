@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.text.Cue
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import androidx.media3.ui.SubtitleView
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.Subtitle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.lang.ref.WeakReference

/**
 * Buffer-preserving sidecar path for external addon subtitles on ExoPlayer.
 *
 * Media3 requires a full [androidx.media3.exoplayer.ExoPlayer.setMediaSource] reload to add
 * [androidx.media3.common.MediaItem.SubtitleConfiguration] tracks mid-playback, which wipes the
 * progressive/VOD buffer. Fast-startup preferred-language auto-select hits that path often.
 *
 * Instead we download + parse with Media3's [DefaultSubtitleParserFactory], then push active
 * [Cue]s to [SubtitleView] while leaving the video MediaPeriod untouched.
 *
 * Failures stay on this path (retry download / multi-mime parse / lenient SRT·VTT fallback).
 * We intentionally do **not** fall back to media reload — that was the main source of mid-play
 * infinite buffering when a flaky subtitle host failed once.
 */
private val sidecarParserFactory = DefaultSubtitleParserFactory()
private val mainHandler = Handler(Looper.getMainLooper())

/**
 * Whether [subtitle] can be hot-attached without [androidx.media3.exoplayer.ExoPlayer.setMediaSource].
 *
 * ASS/SSA are excluded when libass is requested/active: those must stay on the media-source path so
 * [io.github.peerless2012.ass.media.parser.AssSubtitleParserFactory] / AssSubtitleView keep full
 * styling. SRT/VTT/TTML still use the buffer-preserving sidecar.
 */
internal fun PlayerRuntimeController.canAttachAddonSubtitleViaSidecar(subtitle: Subtitle): Boolean {
    val mime = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)
    // Preserve pre-sidecar libass behavior for external ASS/SSA addons.
    if (mime == MimeTypes.TEXT_SSA && shouldKeepAssOnLibassMediaPath()) {
        return false
    }
    val format = Format.Builder().setSampleMimeType(mime).build()
    return sidecarParserFactory.supportsFormat(format)
}

/**
 * User wants libass (or the current Exo player already runs the libass pipeline).
 * In that case external ASS/SSA must not be rendered via Media3's basic SsaParser sidecar.
 */
private fun PlayerRuntimeController.shouldKeepAssOnLibassMediaPath(): Boolean {
    if (isUsingMpvEngine()) return false
    return requestedUseLibassByUser || activePlayerUsesLibass
}

internal fun PlayerRuntimeController.isSidecarAddonSubtitleActive(): Boolean {
    return activeSidecarSubtitleKey != null && _uiState.value.selectedAddonSubtitle != null
}

internal fun PlayerRuntimeController.bindExoSubtitleView(subtitleView: SubtitleView?) {
    exoSubtitleViewRef = subtitleView?.let { WeakReference(it) }
    if (subtitleView == null && activeSidecarSubtitleKey != null) {
        // View unbound while sidecar still active; ticker will no-op until rebound.
        return
    }
    if (activeSidecarSubtitleKey != null && sidecarTimedCues.isNotEmpty()) {
        renderSidecarCuesAtCurrentPosition()
    }
}

internal fun PlayerRuntimeController.stopSidecarAddonSubtitle(clearView: Boolean = true) {
    sidecarSubtitleJob?.cancel()
    sidecarSubtitleJob = null
    activeSidecarSubtitleKey = null
    sidecarTimedCues = emptyList()
    lastSidecarCueSignature = null
    if (clearView) {
        postToSubtitleView { view ->
            view.setTag(R.id.player_view_sidecar_generation_tag, null)
            view.setCues(emptyList())
        }
    }
}

/**
 * Downloads, parses, and starts rendering [subtitle] without reloading the media source.
 * Returns false if the format is unsupported or a newer selection superseded this request.
 */
internal fun PlayerRuntimeController.startSidecarAddonSubtitle(subtitle: Subtitle): Boolean {
    if (!canAttachAddonSubtitleViaSidecar(subtitle)) return false

    val subtitleKey = addonSubtitleKey(subtitle)
    val urlMimeHint = PlayerSubtitleUtils.mimeTypeFromUrl(subtitle.url)

    sidecarSubtitleJob?.cancel()
    activeSidecarSubtitleKey = subtitleKey
    lastSidecarCueSignature = null
    sidecarTimedCues = emptyList()
    postToSubtitleView { view ->
        view.setTag(R.id.player_view_sidecar_generation_tag, subtitleKey)
        view.setCues(emptyList())
    }

    sidecarSubtitleJob = scope.launch {
        try {
            val rawBody = downloadSubtitleBody(subtitle.url, subtitle.lang)
            if (activeSidecarSubtitleKey != subtitleKey) return@launch

            val resolvedMime = PlayerSubtitleUtils.sniffSubtitleMimeType(rawBody, subtitle.url)
            val parseResult = withContext(Dispatchers.Default) {
                parseSidecarTimedCuesRobust(rawBody, subtitle.url)
            }
            if (activeSidecarSubtitleKey != subtitleKey) return@launch

            if (parseResult.cues.isEmpty()) {
                Log.w(
                    PlayerRuntimeController.TAG,
                    "Sidecar subtitle parse empty for id=${subtitle.id} " +
                        "urlMime=$urlMimeHint sniffed=$resolvedMime " +
                        "(buffer preserved; no media reload)"
                )
                // Stay on sidecar path — do not wipe progressive buffer via setMediaSource.
                activeSidecarSubtitleKey = null
                sidecarTimedCues = emptyList()
                postToSubtitleView { view ->
                    view.setTag(R.id.player_view_sidecar_generation_tag, null)
                    view.setCues(emptyList())
                }
                return@launch
            }

            sidecarTimedCues = parseResult.cues
            Log.d(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle ready id=${subtitle.id} cues=${parseResult.cues.size} " +
                    "mime=${parseResult.effectiveMime} source=${parseResult.source} " +
                    "(buffer preserved)"
            )

            while (isActive && activeSidecarSubtitleKey == subtitleKey) {
                renderSidecarCuesAtCurrentPosition()
                delay(SIDECAR_RENDER_INTERVAL_MS)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (activeSidecarSubtitleKey != subtitleKey) return@launch
            Log.w(
                PlayerRuntimeController.TAG,
                "Sidecar subtitle failed id=${subtitle.id}: ${e.message} " +
                    "(buffer preserved; no media reload)"
            )
            activeSidecarSubtitleKey = null
            sidecarTimedCues = emptyList()
            postToSubtitleView { view ->
                view.setTag(R.id.player_view_sidecar_generation_tag, null)
                view.setCues(emptyList())
            }
            // Intentionally no attachAddonSubtitleViaMediaReload — keeps A/V buffer intact.
        }
    }
    return true
}

internal fun PlayerRuntimeController.renderSidecarCuesAtCurrentPosition() {
    val cues = sidecarTimedCues
    if (cues.isEmpty() || activeSidecarSubtitleKey == null) return
    val player = _exoPlayer ?: return
    val positionUs = (
        player.currentPosition.coerceAtLeast(0L) * 1_000L +
            audioDelayUs.get() -
            subtitleDelayUs.get()
        ).coerceAtLeast(0L)
    val active = collectActiveSidecarCues(cues, positionUs).let { current ->
        val sanitized = current.map { SubtitleMojibakeSanitizer.sanitizeCue(it) }
        if (currentPlayerSettingsForReport.subtitleStyle.stripSdh) SubtitleSdhFilter.filterCues(sanitized) else sanitized
    }
    val merged = PlayerSubtitleUtils.mergeOverlappingCues(active)
    val signature = activeCueSignature(merged)
    if (signature == lastSidecarCueSignature) return
    lastSidecarCueSignature = signature
    val currentKey = activeSidecarSubtitleKey ?: return
    postToSubtitleView { view ->
        if (view.getTag(R.id.player_view_sidecar_generation_tag) == currentKey) {
            view.setCues(merged)
        }
    }
}

private fun PlayerRuntimeController.postToSubtitleView(block: (SubtitleView) -> Unit) {
    val view = exoSubtitleViewRef?.get() ?: return
    if (Looper.myLooper() == Looper.getMainLooper()) {
        block(view)
    } else {
        mainHandler.post {
            exoSubtitleViewRef?.get()?.let(block)
        }
    }
}

internal data class SidecarParseResult(
    val cues: List<CuesWithTiming>,
    val effectiveMime: String,
    val source: String
)

/**
 * Tries Media3 parsers across sniffed + common mime types, then a lenient SRT/VTT fallback.
 */
internal fun parseSidecarTimedCuesRobust(rawText: String, sourceUrl: String): SidecarParseResult {
    val cleaned = SubtitleMojibakeSanitizer.sanitize(rawText.replace("\uFEFF", "")).toString()
    val candidates = PlayerSubtitleUtils.sidecarMimeCandidates(cleaned, sourceUrl)
    for (mime in candidates) {
        val parsed = parseSidecarTimedCuesWithMime(cleaned, mime)
        if (parsed.isNotEmpty()) {
            val fixed = PlayerSubtitleRtlFix.fixTimedCues(parsed, isBuiltInSubtitle = false)
            val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
            return SidecarParseResult(
                normalized,
                mime,
                source = "media3"
            )
        }
    }

    val lenient = parseSidecarTimedCuesLenient(cleaned, sourceUrl)
    if (lenient.isNotEmpty()) {
        val mime = PlayerSubtitleUtils.sniffSubtitleMimeType(cleaned, sourceUrl)
        val fixed = PlayerSubtitleRtlFix.fixTimedCues(lenient, isBuiltInSubtitle = false)
        val normalized = if (mime == MimeTypes.TEXT_VTT) normalizeTimedCuePositions(fixed) else fixed
        return SidecarParseResult(
            normalized,
            mime,
            source = "lenient"
        )
    }

    return SidecarParseResult(
        cues = emptyList(),
        effectiveMime = candidates.firstOrNull() ?: MimeTypes.APPLICATION_SUBRIP,
        source = "none"
    )
}

private fun normalizeTimedCuePositions(cues: List<CuesWithTiming>): List<CuesWithTiming> {
    return cues.map { entry ->
        val normalized = entry.cues.map { normalizeSidecarCuePosition(it) }
        val durationUs = when {
            entry.durationUs != C.TIME_UNSET -> entry.durationUs
            entry.endTimeUs != C.TIME_UNSET && entry.startTimeUs != C.TIME_UNSET ->
                (entry.endTimeUs - entry.startTimeUs).coerceAtLeast(1L)
            else -> C.TIME_UNSET
        }
        CuesWithTiming(normalized, entry.startTimeUs, durationUs)
    }
}

private fun parseSidecarTimedCuesWithMime(rawText: String, mimeType: String): List<CuesWithTiming> {
    val format = Format.Builder().setSampleMimeType(mimeType).build()
    if (!sidecarParserFactory.supportsFormat(format)) return emptyList()
    return try {
        val parser = sidecarParserFactory.create(format)
        val data = rawText.toByteArray(Charsets.UTF_8)
        val out = ArrayList<CuesWithTiming>(256)
        parser.parse(data, SubtitleParser.OutputOptions.allCues()) { cueGroup ->
            if (cueGroup.startTimeUs != C.TIME_UNSET) {
                out.add(cueGroup)
            }
        }
        out.sortBy { it.startTimeUs }
        out
    } catch (e: Exception) {
        Log.d(
            PlayerRuntimeController.TAG,
            "Sidecar Media3 parse failed mime=$mimeType: ${e.message}"
        )
        emptyList()
    }
}

/**
 * Lenient SRT/VTT path using [PlayerSubtitleCueParser] when Media3 rejects slightly malformed files.
 * Uses each cue's exact [SubtitleSyncCue.endTimeMs] (not stretched to the next cue start).
 */
internal fun parseSidecarTimedCuesLenient(rawText: String, sourceUrl: String): List<CuesWithTiming> {
    val syncCues = try {
        PlayerSubtitleCueParser.parseFromText(rawText, sourceUrl)
            .filter { it.text.isNotBlank() && it.startTimeMs >= 0L }
    } catch (_: Exception) {
        emptyList()
    }
    if (syncCues.isEmpty()) return emptyList()

    val out = ArrayList<CuesWithTiming>(syncCues.size)
    for (i in syncCues.indices) {
        val startUs = syncCues[i].startTimeMs * 1_000L
        val endUs = (syncCues[i].endTimeMs * 1_000L).coerceAtLeast(startUs + 1L)
        val durationUs = (endUs - startUs).coerceAtLeast(1L)
        val cue = Cue.Builder().setText(syncCues[i].text).build()
        out.add(CuesWithTiming(listOf(cue), startUs, durationUs))
    }
    return out
}

private fun collectActiveSidecarCues(
    cues: List<CuesWithTiming>,
    positionUs: Long
): List<Cue> {
    if (cues.isEmpty()) return emptyList()
    val active = ArrayList<Cue>(4)
    for (entry in cues) {
        if (entry.startTimeUs > positionUs) break
        val end = when {
            entry.endTimeUs != C.TIME_UNSET -> entry.endTimeUs
            entry.durationUs != C.TIME_UNSET -> entry.startTimeUs + entry.durationUs
            else -> Long.MAX_VALUE
        }
        if (positionUs < end) {
            active.addAll(entry.cues)
        }
    }
    return active
}

private fun activeCueSignature(cues: List<Cue>): Long {
    if (cues.isEmpty()) return EMPTY_CUE_SIGNATURE
    var hash = cues.size.toLong()
    for (cue in cues) {
        hash = 31L * hash + (cue.text?.hashCode()?.toLong() ?: 0L)
        hash = 31L * hash + cue.line.toBits().toLong()
        hash = 31L * hash + cue.position.toBits().toLong()
        hash = 31L * hash + cue.lineAnchor.toLong()
        hash = 31L * hash + cue.positionAnchor.toLong()
        hash = 31L * hash + cue.size.toBits().toLong()
    }
    return hash
}

private fun normalizeSidecarCuePosition(cue: Cue): Cue {
    if (cue.bitmap != null || cue.verticalType != Cue.TYPE_UNSET || cue.line == Cue.DIMEN_UNSET) {
        return cue
    }
    return cue.buildUpon()
        .setLine(Cue.DIMEN_UNSET, Cue.TYPE_UNSET)
        .setLineAnchor(Cue.TYPE_UNSET)
        .build()
}

private const val SIDECAR_RENDER_INTERVAL_MS = 100L
private const val EMPTY_CUE_SIGNATURE = 0x4E5556494FL // "NUVIO"
