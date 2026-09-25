package com.nuvio.tv.ui.screens.player

import kotlin.math.max

/**
 * Normalized, language-agnostic description of a subtitle cue for Auto Sync.
 *
 * Auto Sync does not recognise words. Text is used only to discard non-dialogue cues and to
 * reduce the influence of captions that are less likely to represent spoken dialogue.
 */
internal data class SubtitleAutoSyncCueFeature(
    val startMs: Long,
    val endMs: Long,
    val weight: Double,
    val normalizedText: String
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

internal object SubtitleAutoSyncCueProfile {
    private val ASS_OVERRIDE_TAG_REGEX = Regex("""\{\\[^{}]*\}""")
    private val HTML_TAG_REGEX = Regex("""<[^>]+>""")
    private val BRACKETED_FRAGMENT_REGEX = Regex("""\[[^\]]*]|\([^)]*\)""")
    private val SPEAKER_PREFIX_REGEX = Regex("""(?m)^\s*[-–—]?\s*[\p{L}\p{N}][\p{L}\p{N} ._'’-]{0,28}:\s*""")
    private val WHITESPACE_REGEX = Regex("""\s+""")

    private const val MIN_CUE_MS = 100L
    private const val MAX_CUE_MS = 15_000L

    fun features(cues: List<SubtitleSyncCue>): List<SubtitleAutoSyncCueFeature> =
        cues.asSequence()
            .mapNotNull(::feature)
            .sortedBy { it.startMs }
            .toList()

    fun feature(cue: SubtitleSyncCue): SubtitleAutoSyncCueFeature? {
        val startMs = cue.startTimeMs.coerceAtLeast(0L)
        val endMs = max(cue.startTimeMs + MIN_CUE_MS, cue.endTimeMs)
        val durationMs = endMs - startMs
        if (durationMs !in MIN_CUE_MS..MAX_CUE_MS) return null

        val withoutMarkup = cue.text
            .replace(ASS_OVERRIDE_TAG_REGEX, " ")
            .replace(HTML_TAG_REGEX, " ")
        val withoutEffects = withoutMarkup
            .replace(BRACKETED_FRAGMENT_REGEX, " ")
            .replace('♪', ' ')
            .replace('♫', ' ')
        val spokenLines = withoutEffects
            .lineSequence()
            .map { line -> line.replace(SPEAKER_PREFIX_REGEX, " ").trim() }
            .filter { line -> line.any { it.isLetterOrDigit() } }
            .toList()
        val normalized = spokenLines
            .joinToString(" ")
            .replace(WHITESPACE_REGEX, " ")
            .trim()
        val alphaNumericCount = normalized.count { it.isLetterOrDigit() }
        if (alphaNumericCount < 2) return null

        val originalAlphaNumeric = withoutMarkup.count { it.isLetterOrDigit() }.coerceAtLeast(1)
        val spokenFraction = alphaNumericCount.toDouble() / originalAlphaNumeric.toDouble()
        var weight = (0.55 + spokenFraction * 0.45).coerceIn(0.55, 1.0)
        if ('♪' in cue.text || '♫' in cue.text) weight *= 0.55
        if ("\\an8" in cue.text || "\\pos" in cue.text) weight *= 0.75
        if (spokenLines.size > 2) weight *= 0.85

        return SubtitleAutoSyncCueFeature(
            startMs = startMs,
            endMs = endMs,
            weight = weight.coerceIn(0.20, 1.0),
            normalizedText = normalized
        )
    }
}
