package com.nuvio.tv.core.plugin

import com.nuvio.tv.domain.model.scraperIdFromPluginBingeGroup

data class PluginProviderPlayStat(
    val count: Int,
    val latestIndex: Int
)

fun pluginProviderPlayStats(plays: List<String>): Map<String, PluginProviderPlayStat> {
    if (plays.isEmpty()) return emptyMap()
    val stats = LinkedHashMap<String, PluginProviderPlayStat>()
    plays.forEachIndexed { index, raw ->
        val id = raw.trim()
        if (id.isEmpty()) return@forEachIndexed
        val previous = stats[id]
        stats[id] = PluginProviderPlayStat(
            count = (previous?.count ?: 0) + 1,
            latestIndex = index
        )
    }
    return stats
}

data class PluginRunPlan<T>(
    val preferred: T?,
    val others: List<T>
)

/**
 * For next-episode playback, [preferred] is the only scraper to run first.
 * [others] run only when that scraper returns nothing.
 * A normal search leaves [preferred] empty and runs [others] together.
 */
fun <T> List<T>.planPluginRun(
    id: (T) -> String,
    priorityBingeGroup: String?,
    stats: Map<String, PluginProviderPlayStat>,
    preferredOnly: Boolean
): PluginRunPlan<T> {
    val ordered = orderForPluginPlayback(id, priorityBingeGroup, stats)
    if (!preferredOnly) return PluginRunPlan(preferred = null, others = ordered)
    val preferred = preferredPluginProvider(id, priorityBingeGroup, stats)
        ?: return PluginRunPlan(preferred = null, others = ordered)
    return PluginRunPlan(
        preferred = preferred,
        others = ordered.filter { id(it) != id(preferred) }
    )
}

fun <T> List<T>.preferredPluginProvider(
    id: (T) -> String,
    priorityBingeGroup: String?,
    stats: Map<String, PluginProviderPlayStat>
): T? {
    if (isEmpty()) return null
    val priorityId = scraperIdFromPluginBingeGroup(priorityBingeGroup)
        ?.takeIf { scraperId -> any { id(it) == scraperId } }
    val favoriteId = priorityId ?: mostPlayedProviderId(id, stats) ?: return null
    return firstOrNull { id(it) == favoriteId }
}

/**
 * The provider binge group is asking to continue runs first.
 * Otherwise the provider the user has played most runs first.
 * Everyone else keeps the order they were added.
 */
fun <T> List<T>.orderForPluginPlayback(
    id: (T) -> String,
    priorityBingeGroup: String?,
    stats: Map<String, PluginProviderPlayStat>
): List<T> {
    if (size < 2) return this
    val priorityId = scraperIdFromPluginBingeGroup(priorityBingeGroup)
        ?.takeIf { scraperId -> any { id(it) == scraperId } }
    val favoriteId = priorityId ?: mostPlayedProviderId(id, stats)
    if (favoriteId == null) return this
    val favoriteIndex = indexOfFirst { id(it) == favoriteId }
    if (favoriteIndex <= 0) return this
    val ordered = toMutableList()
    ordered.add(0, ordered.removeAt(favoriteIndex))
    return ordered
}

private fun <T> List<T>.mostPlayedProviderId(
    id: (T) -> String,
    stats: Map<String, PluginProviderPlayStat>
): String? {
    var favoriteId: String? = null
    var favoriteStat: PluginProviderPlayStat? = null
    for (item in this) {
        val stat = stats[id(item)] ?: continue
        val current = favoriteStat
        if (current == null ||
            stat.count > current.count ||
            (stat.count == current.count && stat.latestIndex > current.latestIndex)
        ) {
            favoriteId = id(item)
            favoriteStat = stat
        }
    }
    return favoriteId
}
