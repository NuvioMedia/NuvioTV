package com.nuvio.tv.data.repository

internal data class EpisodeMappingEntry(
    val season: Int,
    val episode: Int,
    val title: String? = null,
    val videoId: String? = null
)

internal fun remapEpisodeByTitleOrIndex(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String?,
    addonEpisodes: List<EpisodeMappingEntry>,
    traktEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? {
    if (addonEpisodes.isEmpty() || traktEpisodes.isEmpty()) return null

    val orderedAddonEpisodes = addonEpisodes
        .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
    val orderedTraktEpisodes = traktEpisodes
        .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))

    val currentAddonEpisode = requestedVideoId
        ?.takeIf { it.isNotBlank() }
        ?.let { videoId ->
            orderedAddonEpisodes.firstOrNull { it.videoId == videoId }
        }
        ?: orderedAddonEpisodes.firstOrNull {
            it.season == requestedSeason && it.episode == requestedEpisode
        }
        ?: return null

    val normalizedTitle = normalizeEpisodeTitle(currentAddonEpisode.title)
    if (isUsefulEpisodeTitle(normalizedTitle)) {
        val titleMatches = orderedTraktEpisodes.filter {
            normalizeEpisodeTitle(it.title) == normalizedTitle
        }
        if (titleMatches.size == 1) {
            return titleMatches.first()
        }
    }

    val addonIndex = orderedAddonEpisodes.indexOf(currentAddonEpisode)
    if (addonIndex !in orderedTraktEpisodes.indices) return null

    return orderedTraktEpisodes[addonIndex]
}

private fun normalizeEpisodeTitle(title: String?): String {
    return title
        .orEmpty()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), " ")
        .trim()
        .replace(Regex("\\s+"), " ")
}

private fun isUsefulEpisodeTitle(normalizedTitle: String): Boolean {
    if (normalizedTitle.isBlank()) return false
    if (normalizedTitle.matches(Regex("episode \\d+"))) return false
    if (normalizedTitle.matches(Regex("ep \\d+"))) return false
    if (normalizedTitle.matches(Regex("e \\d+"))) return false
    return true
}
