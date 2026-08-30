package com.nuvio.tv.ui.screens.player

import androidx.media3.common.text.Cue
import androidx.media3.common.text.CueGroup
import androidx.media3.exoplayer.text.TextOutput

internal object SubtitleSdhFilter {
    private val squareBrackets = Regex("\\[[^]]*][ \\t]*")
    // ">>" marks a speaker change and ">>>" a topic change in CEA-608 style
    // captions, which YouTube carries into its own caption tracks.
    private val speakerChevrons = Regex("[<>]{2,}[ \t]*")
    private val parentheses = Regex(
        "(?:\\((?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\\))(?![0-9]*\\))[^)]*\\)|" +
            "\uFF08(?=[A-Za-z0-9 '#.,\\\"\\\\\\-\\r\\n]*\uFF09)(?![0-9]*\uFF09)[^\uFF09]*\uFF09)[ \\t]*"
    )
    private val speakerLabel = Regex(
        "(?m)^([ \\t]*-[ \\t]*)?(?:[A-Za-z0-9 ()'#.,]+|\\[[^]\\r\\n]*]):(?=\\s|$)[ \\t]*"
    )

    fun filterCues(cues: List<Cue>): List<Cue> = cues.mapNotNull { cue ->
        val text = cue.text?.toString() ?: return@mapNotNull cue
        val filtered = filterPlainText(text) ?: return@mapNotNull null
        if (filtered == text) cue else cue.buildUpon().setText(filtered).build()
    }

    internal fun filterPlainText(text: String): String? {
        // Runs before speakerLabel so that ">> NAME:" loses the chevrons first and
        // is then recognised as a speaker label.
        var filtered = speakerChevrons.replace(text, "")
        filtered = speakerLabel.replace(filtered) { match -> match.groups[1]?.value.orEmpty() }
        filtered = squareBrackets.replace(filtered, "")
        filtered = parentheses.replace(filtered, "")
        return filtered.lines()
            .filter { line -> line.any { !it.isWhitespace() && it != '-' } }
            .joinToString("\n")
            .takeIf(String::isNotBlank)
    }
}

internal class SdhFilteringTextOutput(
    private val delegate: TextOutput,
    private val enabled: () -> Boolean
) : TextOutput {
    override fun onCues(cueGroup: CueGroup) {
        if (!enabled()) {
            delegate.onCues(cueGroup)
            return
        }
        delegate.onCues(
            CueGroup(SubtitleSdhFilter.filterCues(cueGroup.cues), cueGroup.presentationTimeUs)
        )
    }

    @Suppress("DEPRECATION")
    @Deprecated("Uses the deprecated Media3 callback for text outputs.")
    override fun onCues(cues: List<Cue>) {
        delegate.onCues(if (enabled()) SubtitleSdhFilter.filterCues(cues) else cues)
    }
}
