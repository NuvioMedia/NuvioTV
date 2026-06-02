package com.nuvio.tv.core.streams

import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBadge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class StreamBadgePresentation @Inject constructor(
    private val dataStore: StreamBadgeSettingsDataStore
) {
    private val badgeFilterCache = AtomicReference<Pair<StreamBadgeRules, List<CompiledStreamBadgeFilter>>?>()

    suspend fun apply(groups: List<AddonStreams>): List<AddonStreams> {
        return withContext(Dispatchers.Default) {
            apply(groups, dataStore.settings.first().rules)
        }
    }

    fun apply(groups: List<AddonStreams>, rules: StreamBadgeRules): List<AddonStreams> {
        val filters = getBadgeFilters(rules)
        if (filters.isEmpty()) return groups
        return groups.map { group ->
            group.copy(
                streams = group.streams.map { stream ->
                    val matchedBadges = StreamBadgeMatcher.matchedBadges(stream, filters)
                    if (matchedBadges.isEmpty()) {
                        stream
                    } else {
                        stream.copy(badges = mergeBadges(stream.badges, matchedBadges))
                    }
                }
            )
        }
    }

    private fun getBadgeFilters(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        val normalized = rules.normalized()
        val cached = badgeFilterCache.get()
        if (cached?.first == normalized) return cached.second
        val compiled = StreamBadgeMatcher.compile(normalized)
        badgeFilterCache.set(normalized to compiled)
        return compiled
    }

    private fun mergeBadges(existing: List<StreamBadge>, matched: List<StreamBadge>): List<StreamBadge> {
        val merged = linkedMapOf<String, StreamBadge>()
        (existing + matched).forEach { badge ->
            val key = badge.imageURL.takeIf { it.isNotBlank() } ?: badge.name
            if (key !in merged) merged[key] = badge
        }
        return merged.values.toList()
    }
}
