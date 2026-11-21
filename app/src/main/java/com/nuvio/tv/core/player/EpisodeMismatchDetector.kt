package com.nuvio.tv.core.player

import java.net.URLDecoder
import java.util.Locale

data class EpisodeMismatchInfo(
    val expectedSeason: Int,
    val expectedEpisode: Int,
    val detectedSeason: Int?,
    val detectedEpisode: Int,
    val detectedLabel: String
) {
    fun formatExpectedCode(): String =
        String.format(Locale.US, "S%02dE%02d", expectedSeason, expectedEpisode)

    fun formatDetectedCode(): String =
        if (detectedSeason != null) {
            String.format(Locale.US, "S%02dE%02d", detectedSeason, detectedEpisode)
        } else {
            String.format(Locale.US, "E%02d", detectedEpisode)
        }
}

object EpisodeMismatchDetector {

    /**
     * Checks resolved filenames or media titles (in priority order) against the requested
     * [expectedSeason] and [expectedEpisode]. Returns [EpisodeMismatchInfo] when the first
     * label with an identifiable episode number contradicts the requested episode.
     */
    fun detectMismatch(
        expectedSeason: Int?,
        expectedEpisode: Int?,
        streamUrl: String? = null,
        candidateLabels: List<String?>
    ): EpisodeMismatchInfo? {
        if (expectedSeason == null || expectedEpisode == null) return null

        val queryExpectedEpisodes = extractExpectedEpisodesFromQuery(streamUrl)
        val allowedEpisodes = buildSet {
            add(expectedEpisode)
            addAll(queryExpectedEpisodes)
        }

        for (rawLabel in candidateLabels) {
            val cleaned = rawLabel
                ?.substringBefore('#')
                ?.substringBefore('?')
                ?.substringAfterLast('/')
                ?.let(::decodeIfNeeded)
                ?.trim()
                ?.takeIf { it.isNotBlank() }
                ?: continue

            val (detectedSeason, detectedEpisode) =
                FilenameEpisodeMatcher.extractSeasonEpisode(cleaned) ?: continue

            if ((detectedSeason == null || detectedSeason == expectedSeason) &&
                containsMultiEpisodeMatch(cleaned, expectedEpisode)
            ) {
                return null
            }

            val isMismatch = if (detectedSeason != null) {
                when {
                    detectedSeason == expectedSeason -> detectedEpisode !in allowedEpisodes
                    // Guard against absolute numbering coded as S01E25 on Season 2+ when no query override is present.
                    expectedSeason > 1 &&
                        detectedSeason == 1 &&
                        queryExpectedEpisodes.isEmpty() &&
                        detectedEpisode > expectedEpisode -> false
                    else -> true
                }
            } else {
                when {
                    detectedEpisode in allowedEpisodes -> false
                    expectedSeason == 1 -> true
                    queryExpectedEpisodes.isNotEmpty() -> true
                    detectedEpisode < expectedEpisode -> true
                    else -> false
                }
            }

            return if (isMismatch) {
                EpisodeMismatchInfo(
                    expectedSeason = expectedSeason,
                    expectedEpisode = expectedEpisode,
                    detectedSeason = detectedSeason,
                    detectedEpisode = detectedEpisode,
                    detectedLabel = cleaned
                )
            } else {
                null
            }
        }
        return null
    }

    private val FILENAME_STAR_REGEX = Regex("""(?i)filename\*\s*=\s*(?:[A-Za-z0-9_-]+'[^']*')?("[^"]*"|[^;]+)""")
    private val FILENAME_QUOTED_OR_TOKEN_REGEX = Regex("""(?i)(?<![*])filename\s*=\s*("((?:\\.|[^"\\])*)"|'((?:\\.|[^'\\])*)'|([^;]+))""")
    private val MULTI_EPISODE_SPAN_REGEX = Regex("""(?i)(?<![a-z0-9])S\d{1,3}[\s._-]*E(\d{1,4})(?:[\s._-]*[-–][\s._-]*E?(\d{1,4})|(?:[\s._-]*E(\d{1,4}))+)(?![a-z0-9])""")

    private fun containsMultiEpisodeMatch(label: String, expectedEpisode: Int): Boolean {
        val match = MULTI_EPISODE_SPAN_REGEX.find(label) ?: return false
        val startEp = match.groupValues[1].toIntOrNull() ?: return false
        val rangeEndEp = match.groupValues[2].toIntOrNull()
        if (rangeEndEp != null && expectedEpisode in startEp..rangeEndEp) return true
        return Regex("""(?i)E(\d{1,4})""").findAll(match.value)
            .mapNotNull { it.groupValues[1].toIntOrNull() }
            .contains(expectedEpisode)
    }

    fun extractContentDispositionFilename(contentDisposition: String?): String? {
        if (contentDisposition.isNullOrBlank()) return null
        val utf8Star = FILENAME_STAR_REGEX.find(contentDisposition)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.trim('"', '\'')
            ?.let(::decodeIfNeeded)
            ?.takeIf { it.isNotBlank() }
        if (utf8Star != null) return utf8Star

        val match = FILENAME_QUOTED_OR_TOKEN_REGEX.find(contentDisposition) ?: return null
        val raw = match.groupValues[2].ifEmpty {
            match.groupValues[3].ifEmpty { match.groupValues[4] }
        }
        return raw
            .trim()
            .replace("\\'", "'")
            .replace("\\\"", "\"")
            .let(::decodeIfNeeded)
            .takeIf { it.isNotBlank() }
    }

    private fun extractExpectedEpisodesFromQuery(url: String?): Set<Int> {
        val query = url?.substringAfter('?', missingDelimiterValue = "")
            ?.substringBefore('#')
            ?.takeIf { it.isNotBlank() }
            ?: return emptySet()

        val result = mutableSetOf<Int>()
        for (part in query.split('&')) {
            val key = part.substringBefore('=', missingDelimiterValue = "").trim().lowercase(Locale.US)
            val value = part.substringAfter('=', missingDelimiterValue = "").trim()
            if (key == "local" || key == "absolute" || key == "episode") {
                value.toIntOrNull()?.takeIf { it > 0 }?.let(result::add)
            }
        }
        return result
    }

    private fun decodeIfNeeded(value: String): String {
        if (!value.contains('%')) return value
        return runCatching {
            URLDecoder.decode(value.replace("+", "%2B"), Charsets.UTF_8.name())
        }.getOrDefault(value)
    }
}
