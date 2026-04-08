package com.nuvio.tv.ui.screens.player

internal object PlayerSubtitleCueParser {
    private val timestampRegex = Regex("""(?:(\d+):)?(\d{1,2}):(\d{2})([.,](\d{1,3}))?""")

    fun parseFromText(rawText: String, sourceUrl: String): List<SubtitleSyncCue> {
        val cleanedText = rawText
            .replace("\uFEFF", "")
            .replace("\r\n", "\n")
            .replace('\r', '\n')

        return if (looksLikeVtt(cleanedText, sourceUrl)) {
            parseVtt(cleanedText)
        } else {
            parseSrt(cleanedText)
        }
    }

    private fun looksLikeVtt(text: String, sourceUrl: String): Boolean {
        val normalizedUrl = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        if (normalizedUrl.endsWith(".vtt") || normalizedUrl.endsWith(".webvtt")) return true
        return text.trimStart().startsWith("WEBVTT")
    }

    private fun parseSrt(text: String): List<SubtitleSyncCue> {
        val blocks = text.split(Regex("""\n{2,}"""))
        val cues = mutableListOf<SubtitleSyncCue>()
        for (block in blocks) {
            val lines = block
                .lines()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue

            var index = 0
            if (lines[index].all { it.isDigit() } && index + 1 < lines.size) {
                index++
            }
            val timing = lines.getOrNull(index) ?: continue
            if (!timing.contains("-->")) continue
            val startTimeMs = parseStartTimeMs(timing) ?: continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString(" "))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, text = cueText)
        }
        return cues
    }

    private fun parseVtt(text: String): List<SubtitleSyncCue> {
        val lines = text
            .lines()
            .map { it.trimEnd() }

        val cues = mutableListOf<SubtitleSyncCue>()
        var cursor = 0

        while (cursor < lines.size) {
            val line = lines[cursor].trim()
            if (line.isBlank()) {
                cursor++
                continue
            }
            if (line.startsWith("WEBVTT") || line.startsWith("NOTE")) {
                cursor++
                continue
            }

            var timingLine = line
            var textStart = cursor + 1
            if (!timingLine.contains("-->")) {
                timingLine = lines.getOrNull(cursor + 1)?.trim().orEmpty()
                textStart = cursor + 2
            }
            if (!timingLine.contains("-->")) {
                cursor++
                continue
            }

            val startTimeMs = parseStartTimeMs(timingLine)
            if (startTimeMs == null) {
                cursor++
                continue
            }

            val textParts = mutableListOf<String>()
            var i = textStart
            while (i < lines.size && lines[i].isNotBlank()) {
                textParts += lines[i].trim()
                i++
            }
            val cueText = normalizeCueText(textParts.joinToString(" "))
            if (cueText.isNotBlank()) {
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private fun parseStartTimeMs(timingLine: String): Long? {
        val startToken = timingLine.substringBefore("-->").trim().substringBefore(' ')
        return parseTimestampMs(startToken)
    }

    private fun parseTimestampMs(rawTimestamp: String): Long? {
        val match = timestampRegex.matchEntire(rawTimestamp.trim()) ?: return null
        val hours = match.groupValues[1].toLongOrNull() ?: 0L
        val minutes = match.groupValues[2].toLongOrNull() ?: return null
        val seconds = match.groupValues[3].toLongOrNull() ?: return null
        val millisRaw = match.groupValues[5]
        val millis = when (millisRaw.length) {
            0 -> 0L
            1 -> "${millisRaw}00".toLong()
            2 -> "${millisRaw}0".toLong()
            else -> millisRaw.take(3).toLongOrNull() ?: 0L
        }
        return ((hours * 3600L) + (minutes * 60L) + seconds) * 1000L + millis
    }

    private fun normalizeCueText(text: String): String {
        return text
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace(Regex("""\s+"""), " ")
            .trim()
    }
}

