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
        } else if (looksLikeAss(cleanedText, sourceUrl)) {
            parseAss(cleanedText)
        } else {
            parseSrt(cleanedText)
        }
    }

    private fun looksLikeAss(text: String, sourceUrl: String): Boolean {
        val normalizedUrl = sourceUrl.substringBefore('?').substringBefore('#').lowercase()
        if (normalizedUrl.endsWith(".ass") || normalizedUrl.endsWith(".ssa")) return true
        return text.contains("[Script Info]", ignoreCase = true) || text.contains("[Events]", ignoreCase = true)
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
            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timing) ?: continue
            // Skip zero-duration cues that cause desync after scene transitions (#2757)
            if (endTimeMs - startTimeMs <= 0) continue
            val textLines = lines.drop(index + 1)
            val cueText = normalizeCueText(textLines.joinToString(" "))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
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

            val (startTimeMs, endTimeMs) = parseStartEndTimeMs(timingLine) ?: run {
                cursor++
                continue
            }
            // Skip zero-duration cues that cause desync after scene transitions (#2757)
            if (endTimeMs - startTimeMs <= 0) {
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
                cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
            }
            cursor = i + 1
        }

        return cues
    }

    private fun parseStartEndTimeMs(timingLine: String): Pair<Long, Long>? {
        val parts = timingLine.split("-->")
        if (parts.size != 2) return null
        val startTimeMs = parseTimestampMs(parts[0].trim().substringBefore(' ')) ?: return null
        val endTimeMs = parseTimestampMs(parts[1].trim().substringBefore(' ')) ?: return null
        return startTimeMs to endTimeMs
    }

    private fun parseStartTimeMs(timingLine: String): Long? {
        val startToken = timingLine.substringBefore("-->").trim().substringBefore(' ')
        return parseTimestampMs(startToken)
    }

    // ASS/SSA "[Events]" section: a "Format:" line declares the column order, then each
    // "Dialogue:" line's fields are comma-separated except the last (Text), which can itself
    // contain commas — so it must be split with a field-count limit, not blindly by comma.
    private fun parseAss(text: String): List<SubtitleSyncCue> {
        val cues = mutableListOf<SubtitleSyncCue>()
        var inEvents = false
        var fieldCount = -1
        var startFieldIndex = -1
        var endFieldIndex = -1
        var textFieldIndex = -1

        for (rawLine in text.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            if (line.startsWith("[")) {
                inEvents = line.equals("[Events]", ignoreCase = true)
                continue
            }
            if (!inEvents) continue
            if (line.startsWith("Format:", ignoreCase = true)) {
                val fields = line.substringAfter(":").split(",").map { it.trim().lowercase() }
                fieldCount = fields.size
                startFieldIndex = fields.indexOf("start")
                endFieldIndex = fields.indexOf("end")
                textFieldIndex = fields.indexOf("text")
                continue
            }
            if (!line.startsWith("Dialogue:", ignoreCase = true)) continue
            if (fieldCount < 0 || startFieldIndex < 0 || textFieldIndex < 0) continue
            val fields = line.substringAfter(":").trim().split(",", limit = fieldCount)
            if (fields.size < fieldCount) continue
            val startTimeMs = parseTimestampMs(fields[startFieldIndex].trim()) ?: continue
            val endTimeMs = endFieldIndex.takeIf { it >= 0 }
                ?.let { parseTimestampMs(fields[it].trim()) } ?: startTimeMs
            val cueText = normalizeCueText(stripAssOverrideTags(fields[textFieldIndex]))
            if (cueText.isBlank()) continue
            cues += SubtitleSyncCue(startTimeMs = startTimeMs, endTimeMs = endTimeMs, text = cueText)
        }
        return cues
    }

    private fun stripAssOverrideTags(rawText: String): String {
        return rawText
            .replace(Regex("""\{[^}]*}"""), "")
            .replace("\\N", " ", ignoreCase = true)
            .replace("\\n", " ", ignoreCase = true)
            .replace("\\h", " ", ignoreCase = true)
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
