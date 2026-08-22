package com.nuvio.tv.ui.screens.player

import android.text.SpannableStringBuilder
import android.text.Spanned
import androidx.media3.common.text.Cue

/**
 * Sanitizes common character encoding artifacts where UTF-8 subtitle text was interpreted as
 * Windows-1252 or ISO-8859-1 text (for example, `â™ª` instead of `♪`).
 */
internal object SubtitleMojibakeSanitizer {

    // Longer patterns must precede their shorter fallback prefixes.
    private val replacements = listOf(
        "â™ª" to "♪",
        "â™«" to "♫",
        "â€™" to "’",
        "â€˜" to "‘",
        "â€œ" to "“",
        "â€\u009D" to "”",
        "â€\u009C" to "“",
        "â€\u0098" to "‘",
        "â€\u0099" to "’",
        "â€“" to "–",
        "â€”" to "—",
        "â€¦" to "…",
        "\u00C2\u00A0" to " ",
        "Â¿" to "¿",
        "Â¡" to "¡",
        "Â«" to "«",
        "Â»" to "»",
        "Â " to " ",
        "â™" to "♪",
        "â€" to "”",
        "\uFFFD" to ""
    )

    fun sanitizeCue(cue: Cue): Cue {
        val text = cue.text ?: return cue
        val sanitized = sanitize(text)
        if (sanitized === text || sanitized.contentEquals(text)) return cue
        return cue.buildUpon().setText(sanitized).build()
    }

    fun sanitize(text: CharSequence): CharSequence {
        if (!hasPotentialMojibake(text)) return text

        if (text is Spanned) {
            val builder = SpannableStringBuilder(text)
            var modified = false
            for ((pattern, replacement) in replacements) {
                var index = builder.indexOf(pattern)
                while (index != -1) {
                    builder.replace(index, index + pattern.length, replacement)
                    modified = true
                    index = builder.indexOf(pattern, index + replacement.length)
                }
            }
            return if (modified) builder else text
        }

        var sanitized = text.toString()
        var modified = false
        for ((pattern, replacement) in replacements) {
            if (sanitized.contains(pattern)) {
                sanitized = sanitized.replace(pattern, replacement)
                modified = true
            }
        }
        return if (modified) sanitized else text
    }

    private fun hasPotentialMojibake(text: CharSequence): Boolean {
        for (index in 0 until text.length) {
            if (text[index] == 'â' || text[index] == 'Â' || text[index] == '\uFFFD') return true
        }
        return false
    }
}
