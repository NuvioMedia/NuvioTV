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
import com.nuvio.tv.domain.model.LibrarySourceMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class TrackingSourceController @Inject constructor(
    private val settingsDataStore: TraktSettingsDataStore,
    private val progressProviders: TrackingProgressProviderRegistry,
    private val libraryProviders: TrackingLibraryProviderRegistry,
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
            settingsDataStore.setRememberedWatchProgressSource(null, profileId)
            if (settingsDataStore.getWatchProgressSource(profileId) == source) return
            applyWatchProgressSource(source, profileId)
        }
    }

    suspend fun selectLibrarySourceMode(mode: LibrarySourceMode) {
        val profileId = profileManager.activeProfileId.value
        mutationMutex.withLock {
            if (profileManager.activeProfileId.value != profileId) return
            settingsDataStore.setRememberedLibrarySourceMode(null, profileId)
            if (settingsDataStore.getLibrarySourceMode(profileId) == mode) return
            applyLibrarySourceMode(mode, profileId)
        }
    }

    suspend fun reconcileConnectedProviders(
        connectedProviderIds: Set<TrackingProviderId>
    ): TrackingSourceSelection = mutationMutex.withLock {
        val profileId = profileManager.activeProfileId.value
        val stored = TrackingSourceSelection(
            watchProgressSource = settingsDataStore.getWatchProgressSource(profileId),
            librarySourceMode = settingsDataStore.getLibrarySourceMode(profileId)
        )
        val rememberedProgress = settingsDataStore.getRememberedWatchProgressSource(profileId)
        val rememberedLibrary = settingsDataStore.getRememberedLibrarySourceMode(profileId)
        val requested = TrackingSourceSelection(
            watchProgressSource = rememberedProgress?.takeIf {
                stored.watchProgressSource == WatchProgressSource.NUVIO_SYNC && it.providerId in connectedProviderIds
            } ?: stored.watchProgressSource,
            librarySourceMode = rememberedLibrary?.takeIf {
                stored.librarySourceMode == LibrarySourceMode.LOCAL && it.providerId in connectedProviderIds
            } ?: stored.librarySourceMode
        )
        val effective = effectiveTrackingSourceSelection(requested, connectedProviderIds)
        if (profileManager.activeProfileId.value != profileId) return@withLock effective
        if (stored.watchProgressSource != effective.watchProgressSource) {
            when {
                effective.watchProgressSource == rememberedProgress ->
                    settingsDataStore.setRememberedWatchProgressSource(null, profileId)
                stored.watchProgressSource.providerId != null ->
                    settingsDataStore.setRememberedWatchProgressSource(stored.watchProgressSource, profileId)
            }
            applyWatchProgressSource(effective.watchProgressSource, profileId)
        }
        if (stored.librarySourceMode != effective.librarySourceMode) {
            when {
                effective.librarySourceMode == rememberedLibrary ->
                    settingsDataStore.setRememberedLibrarySourceMode(null, profileId)
                stored.librarySourceMode.providerId != null ->
                    settingsDataStore.setRememberedLibrarySourceMode(stored.librarySourceMode, profileId)
            }
            applyLibrarySourceMode(effective.librarySourceMode, profileId)
        }
        effective
    }

    private suspend fun applyWatchProgressSource(source: WatchProgressSource, profileId: Int) {
        settingsDataStore.setWatchProgressSource(source, profileId)
        if (profileManager.activeProfileId.value != profileId) return
        continueWatchingEnrichmentCache.saveInProgressSnapshot(emptyList(), force = true)
        continueWatchingEnrichmentCache.saveNextUpSnapshot(emptyList(), force = true)
        val provider = source.providerId?.let(progressProviders::provider)
        if (provider != null) {
            if (provider.clearsLocalProgressOnSelection) {
                watchProgressPreferences.clearAllPreservingNonTraktIds(profileId) { contentId ->
                    provider.retainsLocalProgress(contentId)
                }
            }
            watchedItemsPreferences.clearAll(profileId)
            watchedSeriesStateHolder.update(emptySet())
            provider.refresh(TrackingRefreshIntent.USER_INITIATED)
        } else if (source == WatchProgressSource.NUVIO_SYNC) {
            repopulateWatchedItemsFromNuvioSync(profileId)
            if (profileManager.activeProfileId.value == profileId) {
                startupSyncService.requestSyncNow()
            }
        }
    }

    private suspend fun applyLibrarySourceMode(mode: LibrarySourceMode, profileId: Int) {
        settingsDataStore.setLibrarySourceMode(mode, profileId)
        if (profileManager.activeProfileId.value != profileId) return
        mode.providerId?.let(libraryProviders::provider)?.refresh(TrackingRefreshIntent.USER_INITIATED)
    }

    private suspend fun repopulateWatchedItemsFromNuvioSync(profileId: Int) {
        runCatching {
            watchedItemsSyncService.syncSnapshotFromRemote(profileId).getOrElse { return }
        }
    }
}
