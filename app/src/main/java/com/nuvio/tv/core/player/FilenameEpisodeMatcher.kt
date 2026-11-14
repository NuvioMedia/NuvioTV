package com.nuvio.tv.core.player

object FilenameEpisodeMatcher {

    /**
     * Recognized patterns (case-insensitive):
     *  - S01E03, S1E3, s01e03
     *  - S 01 E 03, S 1 E 3
     *  - 1x03, 01x03
     *  - E03, EP03, Ep 03, EP.03 (episode-only, no season)
     */
    fun buildNextFilename(
        filename: String,
        currentSeason: Int?,
        currentEpisode: Int,
        nextSeason: Int?,
        nextEpisode: Int
    ): String? {
        if (filename.isBlank()) return null

        // Try each pattern from most specific to least specific.
        // Return on first match to avoid replacing unrelated numbers.
        for (pattern in patterns) {
            val result = pattern.replace(
                filename, currentSeason, currentEpisode, nextSeason, nextEpisode
            )
            if (result != null) return result
        }
        return null
    }

    /**
     * Checks whether a candidate filename matches the expected next filename.
     * Comparison is case-insensitive.
     */
    fun matches(candidateFilename: String?, expectedFilename: String?): Boolean {
        if (candidateFilename.isNullOrBlank() || expectedFilename.isNullOrBlank()) return false
        return candidateFilename.equals(expectedFilename, ignoreCase = true)
    }


    private interface EpisodePattern {
        fun replace(
            filename: String,
            currentSeason: Int?,
            currentEpisode: Int,
            nextSeason: Int?,
            nextEpisode: Int
        ): String?
    }

    private val sXeY = object : EpisodePattern {
        // Matches S, optional space, season digits, E, optional space, episode digits
        private val regex = Regex(
            """(?i)(S)\s*(\d{1,3})\s*(E)\s*(\d{1,4})"""
        )

        override fun replace(
            filename: String,
            currentSeason: Int?,
            currentEpisode: Int,
            nextSeason: Int?,
            nextEpisode: Int
        ): String? {
            if (currentSeason == null) return null
            val match = regex.find(filename) ?: return null
            val matchedSeason = match.groupValues[2].toIntOrNull() ?: return null
            val matchedEpisode = match.groupValues[4].toIntOrNull() ?: return null
            if (matchedSeason != currentSeason || matchedEpisode != currentEpisode) return null

            // Preserve the original zero-padding width
            val seasonWidth = match.groupValues[2].length
            val episodeWidth = match.groupValues[4].length
            val nextSeasonStr = (nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0')
            val nextEpisodeStr = nextEpisode.toString().padStart(episodeWidth, '0')

            // Reconstruct with same S/E casing and spacing
            val replacement = "${match.groupValues[1]}${nextSeasonStr}${match.groupValues[3]}${nextEpisodeStr}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    private val seasonXepisode = object : EpisodePattern {
        private val regex = Regex("""(?<!\d)(\d{1,2})[xX](\d{1,4})""")

        override fun replace(
            filename: String,
            currentSeason: Int?,
            currentEpisode: Int,
            nextSeason: Int?,
            nextEpisode: Int
        ): String? {
            if (currentSeason == null) return null
            val match = regex.find(filename) ?: return null
            val matchedSeason = match.groupValues[1].toIntOrNull() ?: return null
            val matchedEpisode = match.groupValues[2].toIntOrNull() ?: return null
            if (matchedSeason != currentSeason || matchedEpisode != currentEpisode) return null

            val seasonWidth = match.groupValues[1].length
            val episodeWidth = match.groupValues[2].length
            val sep = filename[match.range.first + match.groupValues[1].length]
            val replacement = "${(nextSeason ?: currentSeason).toString().padStart(seasonWidth, '0')}${sep}${nextEpisode.toString().padStart(episodeWidth, '0')}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    private val episodeOnly = object : EpisodePattern {
        private val regex = Regex("""(?i)\b(E[Pp]?)[.\s]?(\d{1,4})\b""")

        override fun replace(
            filename: String,
            currentSeason: Int?,
            currentEpisode: Int,
            nextSeason: Int?,
            nextEpisode: Int
        ): String? {
            val match = regex.find(filename) ?: return null
            val matchedEpisode = match.groupValues[2].toIntOrNull() ?: return null
            if (matchedEpisode != currentEpisode) return null

            val episodeWidth = match.groupValues[2].length
            val prefix = match.groupValues[1]
            val separator = filename.substring(
                match.range.first + prefix.length,
                match.range.first + prefix.length + (match.value.length - prefix.length - match.groupValues[2].length)
            )
            val replacement = "$prefix$separator${nextEpisode.toString().padStart(episodeWidth, '0')}"
            return filename.replaceRange(match.range, replacement)
        }
    }

    fun extractSeasonEpisode(filename: String): Pair<Int?, Int>? {
        if (filename.isBlank()) return null
        val sanitized = filename.replace(CRC32_BRACKET_REGEX, "")
        // S01E03 pattern
        Regex("""(?i)(?<![a-z0-9])S\s*(\d{1,3})\s*E\s*(\d{1,4})(?![0-9]|(?![eE]\d)[a-z])""").find(sanitized)?.let { match ->
            val season = match.groupValues[1].toIntOrNull()
            val episode = match.groupValues[2].toIntOrNull() ?: return null
            return season to episode
        }
        // 01x03 pattern (excluding 0x hex and 16x9/4x3 aspect ratios)
        Regex("""(?<![a-zA-Z\d])(\d{1,2})[xX](\d{1,4})(?![a-zA-Z\d])""").findAll(sanitized).forEach { match ->
            val season = match.groupValues[1].toIntOrNull() ?: return@forEach
            val episode = match.groupValues[2].toIntOrNull() ?: return@forEach
            val rawEpisode = match.groupValues[2]
            val isAspectRatio = rawEpisode.length == 1 && ((season == 16 && episode == 9) || (season == 4 && episode == 3))
            if (season > 0 && !isAspectRatio) {
                return season to episode
            }
        }
        // E03, EP03, EP.03 pattern (no season)
        Regex("""(?i)(?<![a-z0-9])(?:ep[.\s_-]?(\d{1,4})|(?<![0-9a-f]{4}-)e(\d{2,4}))(?![a-z0-9])""").find(sanitized)?.let { match ->
            val epRaw = match.groupValues[1].ifEmpty { match.groupValues[2] }
            val episode = epRaw.toIntOrNull() ?: return null
            return null to episode
        }
        // Anime dash pattern: "Title - 01 [1080p].mkv", "Title - 08 - 1080p.mkv"
        findAnimeDashEpisodeMatch(sanitized)?.let { match ->
            val episode = match.groupValues[1].toIntOrNull() ?: return null
            return null to episode
        }
        return null
    }

    private fun findAnimeDashEpisodeMatch(input: String): MatchResult? {
        return ANIME_DASH_EPISODE_REGEX.findAll(input).firstOrNull { match ->
            val number = match.groupValues[1].toIntOrNull() ?: return@firstOrNull false
            number !in NON_EPISODE_NUMBERS && number !in 1900..2099
        }
    }

    private val CRC32_BRACKET_REGEX = Regex("""\[[0-9a-fA-F]{8}]""")
    private val ANIME_DASH_EPISODE_REGEX = Regex(
        """(?<=\s[-–]\s)(?<!\s[-–]\s\d{1,4}\s[-–]\s)(\d{1,4})(?:v\d+)?(?=\s*(?:[\[(]|\.[a-zA-Z0-9]{2,4}$|[-–]\s+(?!\d{1,4}\b(?![pP]))|$))"""
    )
    private val NON_EPISODE_NUMBERS = setOf(264, 265, 480, 576, 720, 1080, 2160)

    private val patterns = listOf(sXeY, seasonXepisode, episodeOnly)
}
