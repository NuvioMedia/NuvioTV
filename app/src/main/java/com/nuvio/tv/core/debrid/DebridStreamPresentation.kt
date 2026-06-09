package com.nuvio.tv.core.debrid

import com.nuvio.tv.core.streams.CompiledStreamBadgeFilter
import com.nuvio.tv.core.streams.StreamBadgeMatcher
import com.nuvio.tv.core.streams.StreamBadgeRules
import com.nuvio.tv.data.local.StreamBadgeSettingsDataStore
import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.DebridSettings
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DebridStreamPresentation @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val streamBadgeSettingsDataStore: StreamBadgeSettingsDataStore,
    private val formatter: DebridStreamFormatter
) {
    private val badgeFilterCache = AtomicReference<Pair<StreamBadgeRules, List<CompiledStreamBadgeFilter>>?>()

    suspend fun apply(
        groups: List<AddonStreams>,
        includeBadgeMatches: Boolean = true
    ): List<AddonStreams> {
        return withContext(Dispatchers.Default) {
            apply(
                groups = groups,
                settings = dataStore.settings.first(),
                streamBadgeRules = streamBadgeSettingsDataStore.settings.first().rules,
                includeBadgeMatches = includeBadgeMatches
            )
        }
    }

    fun apply(
        groups: List<AddonStreams>,
        settings: DebridSettings,
        streamBadgeRules: StreamBadgeRules = StreamBadgeRules(),
        includeBadgeMatches: Boolean = true
    ): List<AddonStreams> {
        if (!settings.canResolvePlayableLinks) return groups
        val badgeFilters by lazy {
            if (includeBadgeMatches) getBadgeFilters(streamBadgeRules) else emptyList()
        }
        return groups.map { group ->
            val visibleStreams = group.streams
                .filterNot { stream -> stream.isInactiveResolverStream(settings) }
                .filterNot { stream -> stream.isUncachedDebridStream() }
            val debridStreams = visibleStreams.filter { stream -> stream.isManagedDebridStream() }
            if (debridStreams.isEmpty()) return@map group.copy(streams = visibleStreams)

            val presentedDebridStreams = DirectDebridStreamFilter.applyPreferences(debridStreams, settings)
                .map { stream -> formatter.format(stream, settings, badgeFilters) }
            val passthroughStreams = visibleStreams.filterNot { stream -> stream.isManagedDebridStream() }

            group.copy(streams = presentedDebridStreams + passthroughStreams)
        }
    }

    private fun getBadgeFilters(rules: StreamBadgeRules): List<CompiledStreamBadgeFilter> {
        val cached = badgeFilterCache.get()
        if (cached?.first == rules) return cached.second
        val compiled = StreamBadgeMatcher.compile(rules)
        badgeFilterCache.set(rules to compiled)
        return compiled
    }

    private fun Stream.isManagedDebridStream(): Boolean {
        val status = debridCacheStatus
        return isDirectDebrid() || (
            needsLocalDebridResolve() &&
                status != null &&
                DebridProviders.byId(status.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
                status.state != StreamDebridCacheState.CHECKING
            )
    }

    private fun Stream.isUncachedDebridStream(): Boolean =
        needsLocalDebridResolve() &&
            DebridProviders.byId(debridCacheStatus?.providerId)?.supports(DebridProviderCapability.LocalTorrentCacheCheck) == true &&
            debridCacheStatus?.state == StreamDebridCacheState.NOT_CACHED

    private fun Stream.isInactiveResolverStream(settings: DebridSettings): Boolean {
        val streamProviderId = DebridProviders.byId(clientResolve?.service)?.id ?: return false
        val activeProviderId = settings.activeResolverProviderId ?: return false
        return isDirectDebrid() && streamProviderId != activeProviderId
    }
}
