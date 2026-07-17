package com.nuvio.tv.ui.screens.player

internal data class SrtCue(
    val startMs: Long,
    val endMs: Long,
    val text: String
)

internal data class SrtDocument(val cues: List<SrtCue>) {
    fun encode(): String = buildString {
        cues.forEachIndexed { index, cue ->
            append(index + 1)
            append('\n')
            append(formatTimestamp(cue.startMs))
            append(" --> ")
            append(formatTimestamp(cue.endMs))
            append('\n')
            append(cue.text.trim())
            append("\n\n")
        }
    }

    companion object {
        private val timingPattern = Regex(
            """^\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,3}):(\d{2}):(\d{2})[,.](\d{1,3})(?:\s+.*)?$"""
        )

        fun parse(rawText: String): SrtDocument {
            val normalized = rawText
                .removePrefix("\uFEFF")
                .replace("\r\n", "\n")
                .replace('\r', '\n')
            val lines = normalized.lines()
            val cues = mutableListOf<SrtCue>()
            var cursor = 0

            while (cursor < lines.size) {
                while (cursor < lines.size && lines[cursor].isBlank()) cursor++
                if (cursor >= lines.size) break

                if (lines[cursor].trim().all(Char::isDigit)) cursor++
                val timing = lines.getOrNull(cursor)?.let(timingPattern::matchEntire)
                if (timing == null) {
                    while (cursor < lines.size && lines[cursor].isNotBlank()) cursor++
                    continue
                }
                cursor++

                val startMs = parseTimestamp(timing.groupValues, 1)
                val endMs = parseTimestamp(timing.groupValues, 5)
                val textStart = cursor
                while (cursor < lines.size && lines[cursor].isNotBlank()) cursor++
                val text = lines.subList(textStart, cursor).joinToString("\n").trim()
                if (text.isNotEmpty() && endMs > startMs) {
                    cues += SrtCue(startMs = startMs, endMs = endMs, text = text)
                }
            }
            return SrtDocument(cues.sortedBy(SrtCue::startMs))
        }

        private fun parseTimestamp(groups: List<String>, offset: Int): Long {
            val hours = groups[offset].toLong()
            val minutes = groups[offset + 1].toLong()
            val seconds = groups[offset + 2].toLong()
            val fraction = groups[offset + 3]
            val millis = fraction.padEnd(3, '0').take(3).toLong()
            return ((hours * 60L + minutes) * 60L + seconds) * 1000L + millis
        }

        private fun formatTimestamp(valueMs: Long): String {
            val safeMs = valueMs.coerceAtLeast(0L)
            val hours = safeMs / 3_600_000L
            val minutes = (safeMs / 60_000L) % 60L
            val seconds = (safeMs / 1000L) % 60L
            val millis = safeMs % 1000L
            return "%02d:%02d:%02d,%03d".format(hours, minutes, seconds, millis)
        }
    }
}
