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
    traktEpisodes: List<EpisodeMappingEntry>,
    preferSameNumberOverIndex: Boolean = false
): EpisodeMappingEntry? {
    return remapEpisodeBetweenLists(
        requestedSeason = requestedSeason,
        requestedEpisode = requestedEpisode,
        requestedVideoId = requestedVideoId,
        requestedTitle = requestedTitle,
        sourceEpisodes = addonEpisodes,
        targetEpisodes = traktEpisodes,
        preferSameNumberOverIndex = preferSameNumberOverIndex
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

internal fun hasSameSeasonStructure(
    addonEpisodes: List<EpisodeMappingEntry>,
    providerEpisodes: List<EpisodeMappingEntry>
): Boolean {
    // Compare per-season episode counts, not just the set of season numbers.
    // Anime often uses the same season numbers in both sources but with completely
    // different episode distributions.
    val addonPerSeason = addonEpisodes.groupBy { it.season }.mapValues { it.value.size }
    val providerPerSeason = providerEpisodes.groupBy { it.season }.mapValues { it.value.size }
    return addonPerSeason == providerPerSeason
}

private fun remapEpisodeBetweenLists(
    requestedSeason: Int,
    requestedEpisode: Int,
    requestedVideoId: String?,
    requestedTitle: String?,
    sourceEpisodes: List<EpisodeMappingEntry>,
    targetEpisodes: List<EpisodeMappingEntry>,
    preferSameNumberOverIndex: Boolean = false
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

    // Cache normalized titles so each unique title is computed once, not once per
    // reverseRemap call. For large shows (One Piece: 1181 episodes × 100+ history
    // entries), this avoids ~236,000 redundant regex operations.
    val normalizedTitle = normalizeEpisodeTitle(requestedTitle ?: currentSourceEpisode.title, normalizedTitleCache)
    if (isUsefulEpisodeTitle(normalizedTitle)) {
        val titleMatches = orderedTargetEpisodes.filter {
            normalizeEpisodeTitle(it.title, normalizedTitleCache) == normalizedTitle
        }
        if (titleMatches.size == 1) {
            return titleMatches.first()
        }
    }

    // The whole-show index below shifts every later episode when any earlier season's
    // count differs, so an episode the target also has keeps its number.
    if (preferSameNumberOverIndex) {
        orderedTargetEpisodes.firstOrNull {
            it.season == currentSourceEpisode.season && it.episode == currentSourceEpisode.episode
        }?.let { return it }
    }

    val sourceIndex = orderedSourceEpisodes.indexOf(currentSourceEpisode)
    if (sourceIndex !in orderedTargetEpisodes.indices) return null

    return orderedTargetEpisodes[sourceIndex]
}

private val NON_ALPHANUMERIC = Regex("[^a-z0-9]+")
private val COLLAPSED_SPACES = Regex("\\s+")
private val normalizedTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun normalizeEpisodeTitle(title: String?, cache: java.util.concurrent.ConcurrentHashMap<String, String>): String {
    if (title == null) return ""
    return cache.getOrPut(title) {
        title.lowercase()
            .replace(NON_ALPHANUMERIC, " ")
            .trim()
            .replace(COLLAPSED_SPACES, " ")
    }
}

private fun isUsefulEpisodeTitle(normalizedTitle: String): Boolean {
    if (normalizedTitle.isBlank()) return false
    if (normalizedTitle.matches(Regex("episode \\d+"))) return false
    if (normalizedTitle.matches(Regex("ep \\d+"))) return false
    if (normalizedTitle.matches(Regex("e \\d+"))) return false
    return true
}
