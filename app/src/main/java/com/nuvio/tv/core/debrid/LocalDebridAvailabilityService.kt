package com.nuvio.tv.core.debrid

import com.nuvio.tv.data.local.DebridSettingsDataStore
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamDebridCacheState
import com.nuvio.tv.domain.model.StreamDebridCacheStatus
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalDebridAvailabilityService @Inject constructor(
    private val dataStore: DebridSettingsDataStore,
    private val localDebridService: LocalDebridService
) {
    suspend fun markChecking(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccount() ?: return groups
        return groups.updateAvailabilityStatus { stream ->
            if (stream.localAvailabilityHash() == null || stream.debridCacheStatus?.state == StreamDebridCacheState.CACHED) {
                stream
            } else {
                stream.copy(
                    debridCacheStatus = StreamDebridCacheStatus(
                        providerId = account.provider.id,
                        providerName = account.provider.displayName,
                        state = StreamDebridCacheState.CHECKING
                    )
                )
            }
        }
    }

    suspend fun annotateCachedAvailability(groups: List<AddonStreams>): List<AddonStreams> {
        val account = cacheCheckAccount() ?: return groups
        val hashes = groups.flatMap { group ->
            group.streams.mapNotNull { stream ->
                stream.localAvailabilityHash()
                    ?.takeUnless { stream.debridCacheStatus?.state in FINAL_CACHE_STATES }
            }
        }.distinct()
        if (hashes.isEmpty()) return groups

        val cached = localDebridService.checkCached(account = account, hashes = hashes)
            ?: return groups.updateAvailabilityStatus { stream ->
                val hash = stream.localAvailabilityHash()
                if (hash == null) {
                    stream
                } else {
                    stream.copy(
                        debridCacheStatus = StreamDebridCacheStatus(
                            providerId = account.provider.id,
                            providerName = account.provider.displayName,
                            state = StreamDebridCacheState.UNKNOWN
                        )
                    )
                }
            }

        return groups.updateAvailabilityStatus { stream ->
            val hash = stream.localAvailabilityHash() ?: return@updateAvailabilityStatus stream
            if (stream.debridCacheStatus?.state in FINAL_CACHE_STATES) return@updateAvailabilityStatus stream
            val cachedItem = cached[hash]
            stream.copy(
                debridCacheStatus = StreamDebridCacheStatus(
                    providerId = account.provider.id,
                    providerName = account.provider.displayName,
                    state = if (cachedItem == null) StreamDebridCacheState.NOT_CACHED else StreamDebridCacheState.CACHED,
                    cachedName = cachedItem?.name,
                    cachedSize = cachedItem?.size
                )
            )
        }
    }

    suspend fun isCached(hash: String): Boolean? {
        val account = cacheCheckAccount() ?: return null
        return localDebridService.isCached(account, hash)
    }

    private suspend fun cacheCheckAccount(): DebridServiceCredential? {
        val settings = dataStore.settings.first()
        if (!settings.canResolvePlayableLinks) return null
        return settings.activeResolverCredential
            ?.takeIf { credential -> credential.provider.supports(DebridProviderCapability.LocalTorrentCacheCheck) }
    }
}

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
