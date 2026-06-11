package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups
        val primary = accounts.first()
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = primary.provider.id,
                        providerName = primary.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return groups
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return groups

        val hashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (hashes.isEmpty()) return groups

        // Check ALL configured providers in parallel instead of just the active one.
        // This ensures torrents cached on ANY provider are shown, not just the preferred one.
        val allResults = coroutineScope {
            accounts.map { account ->
                async {
                    val cached = localDebridService.checkCached(account = account, hashes = hashes)
                    account to (cached ?: emptyMap())
                }
            }.map { it.await() }
        }

        // Merge: prefer the user's active provider when multiple have the same hash
        val preferredId = settings.activeResolverProviderId
        val mergedCache = mutableMapOf<String, CacheHit>()
        for ((account, cached) in allResults) {
            for ((hash, item) in cached) {
                val existing = mergedCache[hash]
                if (existing == null || (account.provider.id == preferredId && existing.providerId != preferredId)) {
                    mergedCache[hash] = CacheHit(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        item = item
                    )
                }
            }
        }

        val fallbackAccount = accounts.first()
        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val hit = mergedCache[hash]
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = hit?.providerId ?: fallbackAccount.provider.id,
                    providerName = hit?.providerName ?: fallbackAccount.provider.displayName,
                    state = if (hit != null) StreamDebridCacheState.CACHED else StreamDebridCacheState.NOT_CACHED,
                    cachedName = hit?.item?.name,
                    cachedSize = hit?.item?.size
                )
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val accounts = cacheCheckAccounts()
        if (accounts.isEmpty()) return null
        // Cached on ANY provider means cached
        for (account in accounts) {
            val result = localDebridService.isCached(account, hash)
            if (result == true) return true
        }
        return false
    }

    /**
     * Returns ALL configured providers with cache-check capability,
     * not just the active/preferred one. This is the core of the fix:
     * when TB + PM are both configured, both get checked in parallel.
     */
    private suspend fun cacheCheckAccounts(): List<DebridServiceCredential> {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return emptyList()
        return DebridProviders.configuredServices(settings)
            .filter { it.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

private data class CacheHit(
    val providerId: String,
    val providerName: String,
    val item: LocalDebridCachedItem
)

private val FINAL_CACHE_STATES = setOf(
    StreamDebridCacheState.CACHED,
    StreamDebridCacheState.NOT_CACHED
)

fun Stream.localAvailabilityHash(): String? =
    infoHash
        ?.trim()
        ?.lowercase()
        ?.takeIf { needsLocalDebridResolve() && it.isNotBlank() }

private fun List<AddonStreams>.updateAvailabilityStatus(
    transform: (Stream) -> Stream
): List<AddonStreams> =
    map { group ->
        var changed = false
        val updatedStreams = group.streams.map { stream ->
            val updated = transform(stream)
            if (updated != stream) changed = true
            updated
        }
        if (changed) group.copy(streams = updatedStreams) else group
    }
