package com.nuvio.tv.data.wetrakr

import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TrackingCapability
import com.nuvio.tv.core.tracking.TrackingHistoryItem
import com.nuvio.tv.core.tracking.TrackingHistoryWriter
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.core.tracking.TrackingMutationResult
import com.nuvio.tv.core.tracking.TrackingProvider
import com.nuvio.tv.core.tracking.TrackingProviderDescriptor
import com.nuvio.tv.core.tracking.TrackingProviderId
import com.nuvio.tv.core.tracking.TrackingScrobbleAction
import com.nuvio.tv.core.tracking.TrackingScrobbleEvent
import com.nuvio.tv.core.tracking.TrackingScrobbler
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class WeTrakrTrackingProvider @Inject constructor(
    auth: WeTrakrAuthStore,
    profiles: ProfileManager,
    override val scrobbler: WeTrakrTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.WETRAKR,
        "WeTrakr",
        setOf(
            TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ, TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

@Singleton
class WeTrakrTrackingScrobbler @Inject constructor(
    private val sync: WeTrakrSyncRepository,
    private val service: WeTrakrScrobbleService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.WETRAKR

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        service.scrobble(sync.currentScope(), action, event)
    }
}

@Singleton
class WeTrakrTrackingHistoryWriter @Inject constructor(
    private val sync: WeTrakrSyncRepository,
    private val service: WeTrakrHistoryService
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.WETRAKR

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.add(scope, items)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.remove(scope, items)
    }
}
