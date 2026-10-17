package com.nuvio.tv.data.mdblist

import android.util.Log
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.tracking.TRACKING_SCROBBLE_DIAGNOSTIC_TAG
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
import com.nuvio.tv.core.tracking.scrobbleDiagnosticSummary
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

@Singleton
class MdbListTrackingProvider @Inject constructor(
    auth: MdbListAuthStore,
    profiles: ProfileManager,
    override val scrobbler: MdbListTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.MDBLIST,
        "MDBList",
        setOf(TrackingCapability.AUTHENTICATION, TrackingCapability.LIBRARY_READ, TrackingCapability.LIBRARY_WRITE, TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE, TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE, TrackingCapability.SCROBBLE)
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO), SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}

@Singleton
class MdbListTrackingScrobbler @Inject constructor(
    private val auth: MdbListAuthStore,
    private val sync: MdbListSyncRepository,
    private val service: MdbListScrobbleService
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) {
        val authenticated = auth.state.value.isAuthenticated
        Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "mdblist adapter received action=${action.wireValue} authenticated=$authenticated ${event.scrobbleDiagnosticSummary()}")
        if (!authenticated) {
            Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "mdblist adapter skipped action=${action.wireValue} reason=not_authenticated")
            return
        }
        sync.ensureLoaded()
        try {
            service.scrobble(sync.currentScope(), action, event)
        } catch (error: Throwable) {
            Log.w(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "mdblist adapter failed action=${action.wireValue} error=${error.javaClass.simpleName}:${error.message} ${event.scrobbleDiagnosticSummary()}", error)
            throw error
        }
        Log.d(TRACKING_SCROBBLE_DIAGNOSTIC_TAG, "mdblist adapter complete action=${action.wireValue} ${event.scrobbleDiagnosticSummary()}")
    }
}

@Singleton
class MdbListTrackingHistoryWriter @Inject constructor(
    private val sync: MdbListSyncRepository,
    private val service: MdbListHistoryService
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.MDBLIST

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        // MDBList answers a show-level mark by marking every episode watched, same as Simkl.
        val pushableItems = items.filterNot(TrackingHistoryItem::isWholeSeriesMark)
        if (pushableItems.isEmpty()) return TrackingMutationResult(0)
        return service.add(scope, pushableItems)
    }

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult {
        val scope = sync.currentScope()
        if (scope.profileId != profileId) return TrackingMutationResult(0)
        return service.remove(scope, items)
    }
}
