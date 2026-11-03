package com.nuvio.tv.reshaped.livetv

/**
 * One profile's Live TV data that Reshaped sync carries between devices (see reshaped/sync).
 * Playlists imported from a file sync too: their file goes as its own Drive copy.
 */
internal data class LiveTvSyncData(
    val sources: List<LiveTvSource> = emptyList(),
    val favorites: Set<String> = emptySet(),
    /** The viewer's own playlists by id: each merges as a whole, the later edit winning. */
    val customLists: Map<String, LiveTvCustomList> = emptyMap(),
    val hiddenGroups: Set<String> = emptySet(),
    val hiddenChannels: Set<Long> = emptySet(),
    val groupNames: Map<String, String> = emptyMap(),
    val groupOrder: List<String> = emptyList(),
    /** Each source's own category order, by source identity. */
    val sourceGroupOrders: Map<String, List<String>> = emptyMap(),
    val recent: LiveTvRecentChannel? = null,
    /** The order the viewer keeps the sources in, by identity. Only ever sorts: never adds or removes one. */
    val sourceOrder: List<String> = emptyList(),
)

/**
 * These sources in [order] (identities): the ones it names first, as it has them, then the rest as
 * they were. Sorting only: every source is kept.
 */
internal fun List<LiveTvSource>.inSyncOrder(order: List<String>): List<LiveTvSource> {
    if (order.isEmpty() || size < 2) return this
    val at = HashMap<String, Int>(order.size * 2)
    order.forEachIndexed { index, identity -> at.putIfAbsent(identity, index) }
    return sortedBy { at[it.identity] ?: Int.MAX_VALUE }
}

/** Every source syncs; an imported playlist's file travels as its own Drive copy (reshaped/sync/SyncedPlaylists). */
internal val LiveTvSource.isSyncable: Boolean
    get() = type != LiveTvSourceType.M3u || url.isNotBlank()

/**
 * [current] with the change from [before] to [after] made on top: what sync brought in, without
 * undoing an edit made here while it ran.
 */
internal fun <T> Set<T>.withSyncChange(before: Set<T>, after: Set<T>): Set<T> {
    if (before == after) return this
    val added = after - before
    val removed = before - after
    return (this - removed) + added
}

internal fun <K, V> Map<K, V>.withSyncChange(before: Map<K, V>, after: Map<K, V>): Map<K, V> {
    if (before == after) return this
    val result = toMutableMap()
    before.keys.filterNot(after::containsKey).forEach(result::remove)
    after.forEach { (key, value) -> if (before[key] != value) result[key] = value }
    return result
}

/**
 * Sources are matched by what they are ([LiveTvSource.identity]), since each device gave its own
 * ids. A changed source keeps its id here; a new one keeps the other device's id (so its hidden
 * channels match), unless this device already uses that id.
 */
internal fun List<LiveTvSource>.withSyncChange(before: List<LiveTvSource>, after: List<LiveTvSource>, newId: () -> String): List<LiveTvSource> {
    if (before == after) return this
    val beforeByIdentity = before.associateBy { it.identity }
    val afterByIdentity = after.associateBy { it.identity }
    val result = mapNotNull { source ->
        val key = source.identity
        val was = beforeByIdentity[key]
        val now = afterByIdentity[key]
        when {
            was != null && now == null -> null
            now != null && now != was -> now.copy(id = source.id)
            else -> source
        }
    }.toMutableList()
    val identities = result.mapTo(HashSet()) { it.identity }
    val ids = result.mapTo(HashSet()) { it.id }
    after.forEach { source ->
        if (source.identity in beforeByIdentity || source.identity in identities) return@forEach
        val id = source.id.takeIf { it.isNotBlank() && it !in ids } ?: newId()
        result += source.copy(id = id)
        identities += source.identity
        ids += id
    }
    return result
}

/** [lists] with sync's change from [before] to [after] made on top, oldest first. */
internal fun List<LiveTvCustomList>.withSyncChange(before: Map<String, LiveTvCustomList>, after: Map<String, LiveTvCustomList>): List<LiveTvCustomList> {
    if (before == after) return this
    return associateBy { it.id }.withSyncChange(before, after).values.sortedBy { it.id }
}

internal fun LiveTvStorage.syncData(): LiveTvSyncData = LiveTvSyncData(
    sources = sources().filter { it.isSyncable },
    favorites = favoriteUrls(),
    customLists = customLists().associateBy { it.id },
    hiddenGroups = hiddenGroups(),
    hiddenChannels = hiddenChannelKeys(),
    groupNames = groupNames(),
    groupOrder = groupOrder(),
    sourceGroupOrders = sourceGroupOrders(),
    recent = recentChannel(),
    sourceOrder = sources().filter { it.isSyncable }.map { it.identity },
)
