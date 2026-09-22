package com.nuvio.tv.ui.screens.player

/**
 * Weighted subtitle match score against a stream release name.
 * Ported from ARVIO [weightedSubtitleScore] — returns 0–100.
 *
 * Token weights:
 *   Title word      → 10
 *   Series/episode  → 8  (S01E01)
 *   Release group   → 5  (last dash-separated segment)
 *   Source          → 4  (bluray, webrip, webdl…)
 *   Resolution      → 3  (1080p, 720p…)
 *   Codec/audio     → 2  (x264, aac…)
 *   Noise           → 1  (ntsc, proper, hdr…)
 *   Pure numbers    → 0  (skipped)
 */
internal object SubtitleReleaseScoring {
    /** Minimum release-name score to use an addon as AI translation source. */
    const val AI_ADDON_SOURCE_MIN_SCORE: Int = 50

    private val EPISODE_RE = Regex("s\\d{1,2}e\\d{1,2}|\\d{1,2}x\\d{1,2}", RegexOption.IGNORE_CASE)

    private val RESOLUTIONS = setOf(
        "480p", "576p", "720p", "1080p", "1080i", "2160p", "4k", "4320p", "8k", "uhd"
    )

    private val SOURCES = setOf(
        "bluray", "blu", "bdrip", "bdremux",
        "webrip", "webdl", "web", "hdrip",
        "hdtv", "pdtv", "dsr",
        "dvd", "dvdrip", "dvdscr",
        "remux",
        "amzn", "nf", "hulu", "dsnp", "atvp", "hmax", "pcok", "cr", "pmtp"
    )

    private val CODECS = setOf(
        "x264", "x265", "h264", "h265", "hevc", "avc",
        "xvid", "divx", "vp9", "av1", "mpeg2", "mpeg4"
    )

    private val AUDIO = setOf(
        "aac", "ac3", "eac3", "dts", "atmos", "truehd", "flac",
        "mp3", "opus", "vorbis", "dd5", "dd2", "ddplus"
    )

    private val NOISE = setOf(
        "ntsc", "pal", "proper", "repack", "readnfo", "extended",
        "theatrical", "imax", "hdr", "hdr10", "hdr10plus", "sdr",
        "dv", "dolbyvision", "hlg", "dubbed", "subbed",
        "dl", "rip", "complete", "internal", "limited",
        "dc", "directors", "cut", "unrated", "retail",
        "esub", "engsub", "hsub", "sub", "subs",
        "51", "71", "20",
        "english", "french", "german", "spanish", "italian", "portuguese",
        "arabic", "russian", "chinese", "japanese", "korean", "turkish",
        "hebrew", "hindi", "dutch", "polish", "czech", "hungarian",
        "romanian", "swedish", "norwegian", "danish", "finnish", "greek",
        "thai", "vietnamese", "indonesian", "malay"
    )

    private val FILE_EXT_RE =
        Regex("\\.(mkv|mp4|avi|mov|wmv|flv|m4v|ts|m2ts|srt|vtt|ass|ssa)$", RegexOption.IGNORE_CASE)
    private val SPLIT_SEASON_RE = Regex("(s\\d{1,2})\\s+(e\\d{1,2})", RegexOption.IGNORE_CASE)
    private val PURE_NUMBERS_RE = Regex("\\d+")
    private val SUBTITLE_BRACKET_RE = Regex("^\\[[^]]+]")
    private val SEPARATOR_RE = Regex("[.\\-_\\s]+")

    private fun tokenWeight(token: String): Int = when {
        token.matches(PURE_NUMBERS_RE) -> 0
        EPISODE_RE.matches(token) -> 8
        token in RESOLUTIONS -> 3
        token in SOURCES -> 4
        token in CODECS -> 2
        token in AUDIO -> 2
        token in NOISE -> 1
        else -> 10
    }

    fun score(streamSourceRaw: String, subtitleId: String): Int {
        if (streamSourceRaw.isBlank() || subtitleId.isBlank()) return 0

        val streamSource = streamSourceRaw.replace('+', ' ')
        val cleanId = subtitleId.replace(SUBTITLE_BRACKET_RE, "").trim()

        fun normalise(s: String) = SPLIT_SEASON_RE.replace(s) { it.groupValues[1] + it.groupValues[2] }

        fun bodyAndGroup(s: String): Pair<String, String?> {
            val i = s.lastIndexOf('-')
            return if (i >= 0) {
                val group = s.substring(i + 1).lowercase().trim()
                    .replace(FILE_EXT_RE, "")
                    .ifBlank { null }
                s.substring(0, i) to group
            } else {
                s to null
            }
        }

        val (streamBody, streamGroup) = bodyAndGroup(normalise(streamSource))
        val (subBody, subGroup) = bodyAndGroup(normalise(cleanId))

        val streamTokens = streamBody.lowercase().split(SEPARATOR_RE).filter { it.length > 1 }
        val subTokenSet = subBody.lowercase().split(SEPARATOR_RE).filter { it.length > 1 }.toSet()

        var totalWeight = 0
        var matchedWeight = 0

        for (token in streamTokens) {
            val w = tokenWeight(token)
            if (w == 0) continue
            totalWeight += w
            if (token in subTokenSet) matchedWeight += w
        }

        if (streamGroup != null) {
            totalWeight += 5
            if (streamGroup == subGroup) matchedWeight += 5
        }

        if (totalWeight == 0) return 0
        return (matchedWeight * 100 / totalWeight).coerceIn(0, 100)
    }

    /** Best text to score an addon subtitle against the stream name. */
    fun subtitleScoreKey(id: String, url: String = "", addonName: String = ""): String {
        val fromUrl = url.substringAfterLast('/').substringBefore('?').takeIf { it.length > 3 }.orEmpty()
        return listOf(id, fromUrl, addonName)
            .map { it.replace(SUBTITLE_BRACKET_RE, "").trim() }
            .firstOrNull { it.isNotBlank() && it.length > 2 }
            .orEmpty()
    }
}
