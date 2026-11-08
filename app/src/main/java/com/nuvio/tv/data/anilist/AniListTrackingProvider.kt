package com.nuvio.tv.data.anilist

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
class AniListTrackingScrobbler @Inject constructor(
    private val writes: AniListTrackingWrites
) : TrackingScrobbler {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun scrobble(action: TrackingScrobbleAction, event: TrackingScrobbleEvent) =
        writes.scrobble(action, event)
}

@Singleton
class AniListTrackingHistoryWriter @Inject constructor(
    private val writes: AniListTrackingWrites
) : TrackingHistoryWriter {
    override val providerId = TrackingProviderId.ANILIST

    override suspend fun addToHistory(profileId: Int, items: Collection<TrackingHistoryItem>): TrackingMutationResult =
        writes.addToHistory(profileId, items)

    override suspend fun removeFromHistory(profileId: Int, items: Collection<TrackingMediaReference>): TrackingMutationResult =
        writes.removeFromHistory(profileId, items)
}

@Singleton
class AniListTrackingProvider @Inject constructor(
    auth: AniListAuthStore,
    profiles: ProfileManager,
    override val scrobbler: AniListTrackingScrobbler
) : TrackingProvider {
    override val descriptor = TrackingProviderDescriptor(
        TrackingProviderId.ANILIST,
        "AniList",
        setOf(
            TrackingCapability.AUTHENTICATION,
            TrackingCapability.LIBRARY_READ,
            TrackingCapability.LIBRARY_WRITE,
            TrackingCapability.WATCHED_READ,
            TrackingCapability.WATCHED_WRITE,
            TrackingCapability.PROGRESS_READ,
            TrackingCapability.PROGRESS_WRITE,
            TrackingCapability.SCROBBLE
        )
    )
    override val isAuthenticated = combine(auth.state, profiles.activeProfileId) { authorization, profileId ->
        authorization.isAuthenticated && authorization.scope.profileId == profileId
    }.stateIn(
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
        SharingStarted.Eagerly,
        auth.state.value.isAuthenticated && auth.scope().profileId == profiles.activeProfileId.value
    )
}
