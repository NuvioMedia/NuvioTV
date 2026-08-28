package com.nuvio.tv.core.streams

/**
 * Aliases used by release names, ordered from highest to lowest resolution so a
 * label carrying several tokens resolves to the best one it advertises.
 */
private val RESOLUTION_ALIASES: List<Pair<Int, Regex>> = listOf(
    2160 to listOf("2160p?", "4k", "uhd"),
    1440 to listOf("1440p?", "2k"),
    1080 to listOf("1080p?", "fhd"),
    720 to listOf("720p?", "hd"),
    576 to listOf("576p?"),
    480 to listOf("480p?", "sd"),
    360 to listOf("360p?")
).map { (height, tokens) ->
    height to Regex("(^|[^a-z0-9])(${tokens.joinToString("|")})([^a-z0-9]|$)")
}

/** Catches heights without a dedicated alias, e.g. `540p` or `800p`. */
private val GENERIC_RESOLUTION_REGEX = Regex("(^|[^a-z0-9])(\\d{3,4})p([^a-z0-9]|$)")

private val PLAUSIBLE_HEIGHTS = 100..4320

/**
 * Canonical resolution detection for stream labels. Addon stream mapping, local
 * plugin results and debrid filtering all resolve heights through here so a
 * label is graded the same way everywhere.
 */
object StreamResolution {

    /**
     * Returns the resolution height advertised by the first label that carries
     * one, mirroring the label priority callers pass in (name before title
     * before description), or `null` when nothing recognisable is present.
     */
    fun detect(vararg labels: String?): Int? =
        labels.firstNotNullOfOrNull { label -> detectIn(label) }

    private fun detectIn(label: String?): Int? {
        val text = label?.lowercase()?.trim().orEmpty()
        if (text.isEmpty()) return null

        RESOLUTION_ALIASES.forEach { (height, regex) ->
            if (regex.containsMatchIn(text)) return height
        }

        GENERIC_RESOLUTION_REGEX.find(text)
            ?.groupValues
            ?.get(2)
            ?.toIntOrNull()
            ?.takeIf { height -> height in PLAUSIBLE_HEIGHTS }
            ?.let { height -> return height }

        // Bare numeric labels such as "800" only count when that is the whole value,
        // so years and sizes inside free text are never mistaken for a resolution.
        return text.toIntOrNull()?.takeIf { height -> height in PLAUSIBLE_HEIGHTS }
    }
}
