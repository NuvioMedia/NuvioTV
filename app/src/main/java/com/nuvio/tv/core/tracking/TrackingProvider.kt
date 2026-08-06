package com.nuvio.tv.core.tracking

import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject
import javax.inject.Singleton

enum class TrackingProviderId(val storageId: String) {
    TRAKT("trakt"),
    SIMKL("simkl");

    companion object {
        fun fromStorage(value: String?): TrackingProviderId? {
            val normalized = value?.trim() ?: return null
            return entries.firstOrNull { provider ->
                provider.storageId.equals(normalized, ignoreCase = true) ||
                    provider.name.equals(normalized, ignoreCase = true)
            }
        }
    }
}

enum class TrackingCapability {
    AUTHENTICATION,
    LIBRARY_READ,
    LIBRARY_WRITE,
    WATCHED_READ,
    WATCHED_WRITE,
    PROGRESS_READ,
    PROGRESS_WRITE,
    SCROBBLE,
    COMMENTS,
    RECOMMENDATIONS
}

data class TrackingProviderDescriptor(
    val id: TrackingProviderId,
    val displayName: String,
    val capabilities: Set<TrackingCapability>
)

interface TrackingProvider {
    val descriptor: TrackingProviderDescriptor
    val isAuthenticated: StateFlow<Boolean>
    val scrobbler: TrackingScrobbler?
        get() = null
}

@Singleton
class TrackingProviderRegistry private constructor(providers: Collection<TrackingProvider>) {
    @Inject
    constructor(providers: Set<@JvmSuppressWildcards TrackingProvider>) : this(providers as Collection<TrackingProvider>)

    internal constructor(providers: List<TrackingProvider>) : this(providers as Collection<TrackingProvider>)

    private val providersById = providers.associateBy { it.descriptor.id }

    init {
        require(providersById.size == providers.size)
    }

    fun providers(): List<TrackingProvider> = providersById.values.sortedBy { it.descriptor.id.ordinal }

    fun provider(id: TrackingProviderId): TrackingProvider? = providersById[id]

    fun isAuthenticated(id: TrackingProviderId): Boolean = providersById[id]?.isAuthenticated?.value == true

    fun connectedProviderIds(): Set<TrackingProviderId> = providers()
        .filter { it.isAuthenticated.value }
        .mapTo(linkedSetOf()) { it.descriptor.id }

    fun connectedScrobblers(): List<TrackingScrobbler> = providers()
        .filter { provider ->
            provider.isAuthenticated.value && TrackingCapability.SCROBBLE in provider.descriptor.capabilities
        }
        .mapNotNull(TrackingProvider::scrobbler)
}
