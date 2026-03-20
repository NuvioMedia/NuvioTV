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
    requestedTitle: String? = null,
    addonEpisodes: List<EpisodeMappingEntry>,
    traktEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? {
    return remapEpisodeBetweenLists(
        requestedSeason = requestedSeason,
        requestedEpisode = requestedEpisode,
        requestedVideoId = requestedVideoId,
        requestedTitle = requestedTitle,
        sourceEpisodes = addonEpisodes,
        targetEpisodes = traktEpisodes
    )
}

internal fun reverseRemapEpisodeByTitleOrIndex(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String? = null,
    requestedTitle: String? = null,
    addonEpisodes: List<EpisodeMappingEntry>,
    traktEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? {
    return remapEpisodeBetweenLists(
        requestedSeason = requestedSeason,
        requestedEpisode = requestedEpisode,
        requestedVideoId = requestedVideoId,
        requestedTitle = requestedTitle,
        sourceEpisodes = traktEpisodes,
        targetEpisodes = addonEpisodes
    )
}

private fun remapEpisodeBetweenLists(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String?,
    requestedTitle: String?,
    sourceEpisodes: List<EpisodeMappingEntry>,
    targetEpisodes: List<EpisodeMappingEntry>
): EpisodeMappingEntry? {
    if (sourceEpisodes.isEmpty() || targetEpisodes.isEmpty()) return null

    val orderedSourceEpisodes = sourceEpisodes
        .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))
    val orderedTargetEpisodes = targetEpisodes
        .sortedWith(compareBy(EpisodeMappingEntry::season, EpisodeMappingEntry::episode))

    val currentSourceEpisode = requestedVideoId
        ?.takeIf { it.isNotBlank() }
        ?.let { videoId ->
            orderedSourceEpisodes.firstOrNull { it.videoId == videoId }
        }
        ?: orderedSourceEpisodes.firstOrNull {
            it.season == requestedSeason && it.episode == requestedEpisode
        }
        ?: return null

    val normalizedTitle = normalizeEpisodeTitle(requestedTitle ?: currentSourceEpisode.title)
    if (isUsefulEpisodeTitle(normalizedTitle)) {
        val titleMatches = orderedTargetEpisodes.filter {
            normalizeEpisodeTitle(it.title) == normalizedTitle
        }
        if (titleMatches.size == 1) {
            return titleMatches.first()
        }
    }

    val sourceIndex = orderedSourceEpisodes.indexOf(currentSourceEpisode)
    if (sourceIndex !in orderedTargetEpisodes.indices) return null

    return orderedTargetEpisodes[sourceIndex]
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
