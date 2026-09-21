package com.nuvio.tv.ui.screens.player.subtitles

import android.os.Handler
import android.text.SpannableString
import androidx.annotation.OptIn
import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.text.TextOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private object AiSubtitleRegexes {
    val BRACKET_REGEX = Regex("""\[.*?\]""")
    val MUSIC_REGEX = Regex("[♪♫]+")
}

/**
 * Intercepts ExoPlayer subtitle cues and swaps in AI-translated text when enabled.
 */
@OptIn(UnstableApi::class)
internal class TranslatingTextOutput(
    private val delegate: TextOutput,
    private val manager: SubtitleTranslationManager,
    outputLooper: android.os.Looper,
    private val scope: CoroutineScope
) : TextOutput {

    private val handler = Handler(outputLooper)
    @Volatile private var lastCueGroup: CueGroup? = null
    var onFirstCueOnPlaybackThread: (() -> Unit)? = null
    private var hasFiredFirstCue = false

    override fun onCues(cueGroup: CueGroup) {
        val cues = cueGroup.cues

        if (!hasFiredFirstCue && cues.isNotEmpty() && manager.isEnabled) {
            hasFiredFirstCue = true
            onFirstCueOnPlaybackThread?.invoke()
            onFirstCueOnPlaybackThread = null
        }

        if (!manager.isEnabled) {
            lastCueGroup = cueGroup
            delegate.onCues(cueGroup)
            return
        }
        if (cues.isEmpty()) {
            lastCueGroup = cueGroup
            delegate.onCues(cueGroup)
            return
        }

        val rawText = extractRawText(cues)
        if (rawText.isBlank()) {
            manager.onUntranslatableSource?.invoke()
            lastCueGroup = cueGroup
            delegate.onCues(cueGroup)
            return
        }
        val text = if (manager.removeHearingImpaired) stripHearingImpaired(rawText) else rawText
        if (text.isBlank()) {
            delegate.onCues(CueGroup(emptyList(), cueGroup.presentationTimeUs))
            return
        }

        lastCueGroup = cueGroup
        val cached = manager.getCached(text)
        if (cached != null) {
            delegate.onCues(buildTranslated(cueGroup, cues, cached))
            return
        }

        delegate.onCues(CueGroup(emptyList(), cueGroup.presentationTimeUs))
        val captured = cueGroup
        scope.launch {
            val translated = manager.translate(text)
            handler.post {
                if (lastCueGroup === captured) {
                    delegate.onCues(buildTranslated(captured, captured.cues, translated))
                }
            }
        }
    }

    @Deprecated("Uses the deprecated Media3 callback.")
    override fun onCues(cues: List<Cue>) {
        if (!manager.isEnabled || cues.isEmpty()) {
            delegate.onCues(cues)
            return
        }
        val rawText = extractRawText(cues)
        if (rawText.isBlank()) {
            delegate.onCues(cues)
            return
        }
        val text = if (manager.removeHearingImpaired) stripHearingImpaired(rawText) else rawText
        if (text.isBlank()) {
            delegate.onCues(emptyList())
            return
        }
        val cached = manager.getCached(text)
        when {
            cached != null -> delegate.onCues(applyTranslatedLinesToCues(cues, cached))
            manager.isInFlight(text) -> delegate.onCues(emptyList())
            else -> delegate.onCues(emptyList())
        }
    }

    private fun extractRawText(cues: List<Cue>): String =
        cues.mapNotNull { it.text?.toString()?.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")

    private fun stripHearingImpaired(text: String): String =
        text.replace(AiSubtitleRegexes.BRACKET_REGEX, "")
            .replace(AiSubtitleRegexes.MUSIC_REGEX, "")
            .trim()

    private fun buildTranslated(group: CueGroup, originalCues: List<Cue>, translatedText: String): CueGroup =
        CueGroup(applyTranslatedLinesToCues(originalCues, translatedText), group.presentationTimeUs)

    companion object {
        fun applyTranslatedLinesToCues(originalCues: List<Cue>, translatedText: String): List<Cue> {
            val translatedLines = translatedText.split("\n")
            var lineIndex = 0
            return originalCues.map { cue ->
                val originalLineCount = (cue.text?.toString() ?: "").split("\n").size
                val end = (lineIndex + originalLineCount).coerceAtMost(translatedLines.size)
                val cueText = if (lineIndex < translatedLines.size) {
                    translatedLines.subList(lineIndex, end).joinToString("\n")
                } else {
                    cue.text?.toString() ?: ""
                }
                lineIndex += originalLineCount
                val rtlAware = if (cueText.any { ch ->
                        val dir = Character.getDirectionality(ch)
                        dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT ||
                            dir == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC
                    }
                ) {
                    "‏$cueText‏"
                } else {
                    cueText
                }
                cue.buildUpon().setText(SpannableString(rtlAware)).build()
            }
        }
    }
}
