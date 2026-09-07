package com.nuvio.tv.core.tracking

import com.nuvio.tv.core.sync.StartupSyncService
import com.nuvio.tv.core.sync.WatchedItemsSyncService
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
import com.nuvio.tv.data.local.TraktSettingsDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchProgressSource
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.local.WatchedSeriesStateHolder
import com.nuvio.tv.data.repository.TraktProgressService
import com.nuvio.tv.data.repository.isTraktCompatibleId
import com.nuvio.tv.data.simkl.SimklSyncRepository
import com.nuvio.tv.domain.model.LibrarySourceMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TrackingSourceController @Inject constructor(
    private val settingsDataStore: TraktSettingsDataStore,
    private val traktProgressService: TraktProgressService,
    private val simklSyncRepository: SimklSyncRepository,
    private val startupSyncService: StartupSyncService,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val watchedSeriesStateHolder: WatchedSeriesStateHolder,
    private val continueWatchingEnrichmentCache: ContinueWatchingEnrichmentCache,
    private val profileManager: ProfileManager
) {
    val watchProgressSource = settingsDataStore.watchProgressSource
    val librarySourceMode = settingsDataStore.librarySourceMode

    private val mutationMutex = Mutex()

    suspend fun selectWatchProgressSource(source: WatchProgressSource) {
        val profileId = profileManager.activeProfileId.value
        mutationMutex.withLock {
            if (profileManager.activeProfileId.value != profileId) return
            if (settingsDataStore.getWatchProgressSource(profileId) == source) return
            applyWatchProgressSource(source, profileId)
        }
    }

    suspend fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        val profileId = profileManager.activeProfileId.value
        mutationMutex.withLock {
            if (profileManager.activeProfileId.value != profileId) return
            if (settingsDataStore.getLibrarySourceMode(profileId) == mode) return
            applyLibrarySourceMode(mode, profileId)
        }
    }

    suspend fun reconcileConnectedProviders(
        connectedProviderIds: Set<TrackingProviderId>
    ): TrackingSourceSelection = mutationMutex.withLock {
        val profileId = profileManager.activeProfileId.value
        val requested = TrackingSourceSelection(
            watchProgressSource = settingsDataStore.getWatchProgressSource(profileId),
            librarySourceMode = settingsDataStore.getLibrarySourceMode(profileId)
        )
        val effective = effectiveTrackingSourceSelection(requested, connectedProviderIds)
        if (profileManager.activeProfileId.value != profileId) return@withLock effective
        if (requested.watchProgressSource != effective.watchProgressSource) {
            applyWatchProgressSource(effective.watchProgressSource, profileId)
        }
        if (requested.librarySourceMode != effective.librarySourceMode) {
            applyLibrarySourceMode(effective.librarySourceMode, profileId)
        }
        effective
    }

    private suspend fun applyWatchProgressSource(source: WatchProgressSource, profileId: Int) {
        settingsDataStore.setWatchProgressSource(source, profileId)
        if (profileManager.activeProfileId.value != profileId) return
        continueWatchingEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true)
        continueWatchingEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true)
        when (source) {
            WatchProgressSource.TRAKT -> {
                watchProgressPreferences.clearAllPreservingNonTraktIds(profileId) { contentId ->
                    !isTraktCompatibleId(contentId)
                }
                watchedItemsPreferences.clearAll(profileId)
                watchedSeriesStateHolder.update(emptySet())
                traktProgressService.refreshNow()
            }
            WatchProgressSource.SIMKL -> {
                watchedItemsPreferences.clearAll(profileId)
                watchedSeriesStateHolder.update(emptySet())
                simklSyncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
            }
            WatchProgressSource.NUVIO_SYNC -> {
                repopulateWatchedItemsFromNuvioSync(profileId)
                if (profileManager.activeProfileId.value == profileId) {
                    startupSyncService.requestSyncNow()
                }
            }
        }
    }

    private suspend fun applyLibrarySourceMode(mode: LibrarySourceMode, profileId: Int) {
        settingsDataStore.setLibrarySourceMode(mode, profileId)
        if (profileManager.activeProfileId.value != profileId) return
        if (mode == LibrarySourceMode.SIMKL) {
            simklSyncRepository.refresh(TrackingRefreshIntent.USER_INITIATED)
        }
    }

    private suspend fun repopulateWatchedItemsFromNuvioSync(profileId: Int) {
        runCatching {
            watchedItemsSyncService.syncSnapshotFromRemote(profileId).getOrElse { return }
        }
    }
}
