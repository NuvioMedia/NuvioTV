package com.nuvio.tv.data.simkl

import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.parseTrackingExternalIds

internal class SimklEntryIndex private constructor(
    private val entriesRef: List<SimklLibraryEntry>,
    private val externalIdsByIndex: Array<TrackingExternalIds?>,
    private val providerItemIdByIndex: Array<String?>,
    private val byAlias: Map<String, IntArray>,
    private val byTitle: Map<String, IntArray>
) {
    constructor(entriesRef: List<SimklLibraryEntry>) : this(
        entriesRef = entriesRef,
        externalIdsByIndex = arrayOfNulls(entriesRef.size),
        providerItemIdByIndex = arrayOfNulls(entriesRef.size),
        byAlias = HashMap(maxOf(16, entriesRef.size * 3)),
        byTitle = HashMap(maxOf(16, entriesRef.size))
    ) {
        val aliasMap = byAlias as HashMap<String, IntArray>
        val titleMap = byTitle as HashMap<String, IntArray>

        for (index in entriesRef.indices) {
            val entry = entriesRef[index]
            val media = entry.media ?: continue
            val externalIds = media.toTrackingExternalIds()
            externalIdsByIndex[index] = externalIds
            providerItemIdByIndex[index] = media.simklTrackingProviderItemId()

            externalIds.forEachAliasKey { key ->
                putIndex(aliasMap, key, index)
            }
            media.normalizedTitleKey()?.let { normalizedTitle ->
                putIndex(titleMap, normalizedTitle, index)
            }
        }
    }

    fun matchesEntries(entries: List<SimklLibraryEntry>): Boolean = entries === entriesRef

    fun withUpdatedEntry(
        newEntries: List<SimklLibraryEntry>,
        index: Int,
        updatedEntry: SimklLibraryEntry
    ): SimklEntryIndex {
        val oldEntry = entriesRef.getOrNull(index)
        val oldTitle = oldEntry?.media?.normalizedTitleKey()
        val media = updatedEntry.media
        val newIds = media?.toTrackingExternalIds()
        val newProviderId = media?.simklTrackingProviderItemId()
        val newTitle = media?.normalizedTitleKey()

        if (
            index in externalIdsByIndex.indices &&
            newEntries.size == externalIdsByIndex.size &&
            externalIdsByIndex[index] == newIds &&
            providerItemIdByIndex[index] == newProviderId &&
            oldTitle == newTitle
        ) {
            return SimklEntryIndex(
                entriesRef = newEntries,
                externalIdsByIndex = externalIdsByIndex,
                providerItemIdByIndex = providerItemIdByIndex,
                byAlias = byAlias,
                byTitle = byTitle
            )
        }

        val oldIds = if (index in externalIdsByIndex.indices) externalIdsByIndex[index] else null
        val oldProviderId = if (index in providerItemIdByIndex.indices) providerItemIdByIndex[index] else null
        val idsArrayUnchanged = index in externalIdsByIndex.indices &&
            newEntries.size == externalIdsByIndex.size &&
            oldIds == newIds &&
            oldProviderId == newProviderId
        val nextExternalIds = if (idsArrayUnchanged) {
            externalIdsByIndex
        } else {
            externalIdsByIndex.copyOf(newEntries.size).also {
                if (index in it.indices) it[index] = newIds
            }
        }
        val nextProviderIds = if (idsArrayUnchanged) {
            providerItemIdByIndex
        } else {
            providerItemIdByIndex.copyOf(newEntries.size).also {
                if (index in it.indices) it[index] = newProviderId
            }
        }

        val nextAlias = if (oldIds == newIds) {
            byAlias
        } else {
            HashMap(byAlias).also { aliasMap ->
                if (oldIds != null) {
                    oldIds.forEachAliasKey { key ->
                        removeIndex(aliasMap, key, index)
                    }
                }
                if (newIds != null && index in nextExternalIds.indices) {
                    newIds.forEachAliasKey { key ->
                        putIndexSorted(aliasMap, key, index)
                    }
                }
            }
        }

        val nextTitle = if (oldTitle == newTitle) {
            byTitle
        } else {
            HashMap(byTitle).also { titleMap ->
                if (oldTitle != null) {
                    removeIndex(titleMap, oldTitle, index)
                }
                if (newTitle != null && index in nextExternalIds.indices) {
                    putIndexSorted(titleMap, newTitle, index)
                }
            }
        }

        return SimklEntryIndex(
            entriesRef = newEntries,
            externalIdsByIndex = nextExternalIds,
            providerItemIdByIndex = nextProviderIds,
            byAlias = nextAlias,
            byTitle = nextTitle
        )
    }

    fun firstSharingIdentityWith(
        entries: List<SimklLibraryEntry>,
        targetIds: TrackingExternalIds,
        predicate: ((SimklLibraryEntry) -> Boolean)? = null
    ): SimklLibraryEntry? {
        if (!matchesEntries(entries)) {
            return entries.firstOrNull { candidate ->
                (predicate == null || predicate(candidate)) &&
                    candidate.media?.toTrackingExternalIds()?.sharesIdentityWith(targetIds) == true
            }
        }
        var bestIndex = Int.MAX_VALUE
        targetIds.forEachAliasKey { key ->
            val indices = byAlias[key] ?: return@forEachAliasKey
            for (idx in indices) {
                if (idx >= bestIndex) break
                val candidate = entries[idx]
                val candidateIds = externalIdsByIndex[idx]
                if ((predicate == null || predicate(candidate)) &&
                    candidateIds != null &&
                    candidateIds.sharesIdentityWith(targetIds)
                ) {
                    bestIndex = idx
                    break
                }
            }
        }
        return if (bestIndex != Int.MAX_VALUE) entries[bestIndex] else null
    }

    fun firstMatchingContentId(
        entries: List<SimklLibraryEntry>,
        contentId: String
    ): SimklLibraryEntry? {
        if (!matchesEntries(entries)) {
            return entries.firstOrNull { candidate -> candidate.matchesSimklContentId(contentId) }
        }
        val parsed = parseTrackingExternalIds(contentId)
        val candidates = collectCandidateIndices(parsed, rawContentId = contentId)
        for (idx in candidates) {
            val entry = entries[idx]
            val media = entry.media ?: continue
            if (media.canonicalContentId().equals(contentId, ignoreCase = true)) return entry
            val candidateIds = externalIdsByIndex[idx] ?: continue
            if (parsed.sharesIdentityWith(candidateIds)) return entry
        }
        return null
    }

    fun matchingEntriesForContent(
        entries: List<SimklLibraryEntry>,
        contentId: String,
        trackingProviderItemId: String?
    ): List<SimklLibraryEntry> {
        val parsed = parseTrackingExternalIds(contentId)
        if (!matchesEntries(entries)) {
            return entries.filter { entry ->
                entry.matchesContentWithParsedIds(contentId, parsed, trackingProviderItemId)
            }
        }
        val candidates = collectCandidateIndices(
            parsed = parsed,
            rawContentId = contentId,
            providerItemId = trackingProviderItemId
        )
        if (candidates.isEmpty()) return emptyList()
        val result = ArrayList<SimklLibraryEntry>(candidates.size)
        for (idx in candidates) {
            val entry = entries[idx]
            val providerId = providerItemIdByIndex[idx]
            val sameProviderItem = providerId != null &&
                providerId.equals(trackingProviderItemId, ignoreCase = true)
            if (sameProviderItem) {
                result.add(entry)
                continue
            }
            val media = entry.media ?: continue
            if (media.canonicalContentId().equals(contentId, ignoreCase = true)) {
                result.add(entry)
                continue
            }
            val candidateIds = externalIdsByIndex[idx] ?: continue
            if (parsed.sharesIdentityWith(candidateIds)) {
                result.add(entry)
            }
        }
        return result
    }

    fun indexOfMatchingEntry(
        entries: List<SimklLibraryEntry>,
        result: SimklScrobbleResult
    ): Int {
        if (!matchesEntries(entries)) {
            return entries.indexOfMatchingEntryLinear(result)
        }
        val target = result.media
        val targetIds = target.toTrackingExternalIds()
        val candidates = collectCandidateIndicesForMedia(target, targetIds)
        var fallbackIndex = -1
        for (idx in candidates) {
            val entry = entries[idx]
            val media = entry.media ?: continue
            val candidateIds = externalIdsByIndex[idx] ?: media.toTrackingExternalIds()
            if (media.matchesTargetWithIds(candidateIds, target, targetIds)) {
                if (entry.mediaType == result.mediaType) return idx
                if (fallbackIndex < 0) fallbackIndex = idx
            }
        }
        return fallbackIndex
    }

    fun indexOfMatchingMedia(
        entries: List<SimklLibraryEntry>,
        target: SimklMedia
    ): Int {
        if (!matchesEntries(entries)) {
            return entries.indexOfMatchingMediaLinear(target)
        }
        val targetIds = target.toTrackingExternalIds()
        val candidates = collectCandidateIndicesForMedia(target, targetIds)
        for (idx in candidates) {
            val media = entries[idx].media ?: continue
            val candidateIds = externalIdsByIndex[idx] ?: media.toTrackingExternalIds()
            if (media.matchesTargetWithIds(candidateIds, target, targetIds)) {
                return idx
            }
        }
        return -1
    }

    private fun collectCandidateIndices(
        parsed: TrackingExternalIds,
        rawContentId: String? = null,
        providerItemId: String? = null
    ): IntArray {
        val merged = IntArrayList()
        providerItemId?.trim()?.takeIf(String::isNotEmpty)?.lowercase()?.let { key ->
            byAlias[key]?.let(merged::addAll)
        }
        rawContentId?.trim()?.takeIf(String::isNotEmpty)?.lowercase()?.let { key ->
            byAlias[key]?.let(merged::addAll)
            if (!key.contains(':')) {
                byAlias["imdb:$key"]?.let(merged::addAll)
            }
        }
        parsed.forEachAliasKey { key ->
            byAlias[key]?.let(merged::addAll)
        }
        return merged.toSortedDistinctIntArray()
    }

    private fun collectCandidateIndicesForMedia(
        target: SimklMedia,
        targetIds: TrackingExternalIds
    ): IntArray {
        val merged = IntArrayList()
        targetIds.forEachAliasKey { key ->
            byAlias[key]?.let(merged::addAll)
        }
        target.normalizedTitleKey()?.let { normalizedTitle ->
            byTitle[normalizedTitle]?.let(merged::addAll)
        }
        return merged.toSortedDistinctIntArray()
    }

    private class IntArrayList(initialCapacity: Int = 4) {
        private var data = IntArray(initialCapacity)
        var size = 0
            private set

        fun addAll(elements: IntArray) {
            if (elements.isEmpty()) return
            val required = size + elements.size
            if (required > data.size) {
                var newCapacity = data.size
                while (newCapacity < required) newCapacity *= 2
                data = data.copyOf(newCapacity)
            }
            System.arraycopy(elements, 0, data, size, elements.size)
            size = required
        }

        fun toSortedDistinctIntArray(): IntArray {
            if (size == 0) return EMPTY_INT_ARRAY
            if (size == 1) return intArrayOf(data[0])
            val copy = data.copyOf(size)
            copy.sort()
            var uniqueCount = 1
            for (i in 1 until copy.size) {
                if (copy[i] != copy[uniqueCount - 1]) {
                    copy[uniqueCount++] = copy[i]
                }
            }
            return if (uniqueCount == copy.size) copy else copy.copyOf(uniqueCount)
        }
    }

    private companion object {
        val EMPTY_INT_ARRAY = IntArray(0)

        fun putIndex(map: HashMap<String, IntArray>, key: String, index: Int) {
            val existing = map[key]
            if (existing == null) {
                map[key] = intArrayOf(index)
            } else if (existing[existing.lastIndex] != index) {
                val next = existing.copyOf(existing.size + 1)
                next[existing.size] = index
                map[key] = next
            }
        }

        fun putIndexSorted(map: HashMap<String, IntArray>, key: String, index: Int) {
            val existing = map[key]
            if (existing == null) {
                map[key] = intArrayOf(index)
                return
            }
            if (existing.binarySearch(index) >= 0) return
            val next = existing.copyOf(existing.size + 1)
            next[existing.size] = index
            next.sort()
            map[key] = next
        }

        fun removeIndex(map: HashMap<String, IntArray>, key: String, index: Int) {
            val existing = map[key] ?: return
            val pos = existing.binarySearch(index)
            if (pos < 0) return
            if (existing.size == 1) {
                map.remove(key)
                return
            }
            val next = IntArray(existing.size - 1)
            if (pos > 0) System.arraycopy(existing, 0, next, 0, pos)
            if (pos < existing.lastIndex) System.arraycopy(existing, pos + 1, next, pos, existing.size - pos - 1)
            map[key] = next
        }
    }
}

private fun SimklMedia.normalizedTitleKey(): String? =
    title?.trim()?.takeIf(String::isNotEmpty)?.lowercase()

internal inline fun TrackingExternalIds.forEachAliasKey(action: (String) -> Unit) {
    simkl?.let { action("simkl:$it") }
    imdb?.trim()?.takeIf(String::isNotEmpty)?.lowercase()?.let {
        action("imdb:$it")
        action(it)
    }
    tmdb?.let { action("tmdb:$it") }
    tvdb?.trim()?.takeIf(String::isNotEmpty)?.lowercase()?.let { action("tvdb:$it") }
    mal?.let { action("mal:$it") }
    anidb?.let { action("anidb:$it") }
    anilist?.let { action("anilist:$it") }
    kitsu?.let { action("kitsu:$it") }
}
