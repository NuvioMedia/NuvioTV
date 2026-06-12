package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.StartupSyncPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"
private const val FORCE_RESYNC_MIN_INTERVAL_MS = 30_000L
private const val FULL_STARTUP_PULL_TTL_MS = 6 * 60 * 60 * 1000L

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
    private val collectionSyncService: CollectionSyncService,
    private val homeCatalogSettingsSyncService: HomeCatalogSettingsSyncService,
    private val watchProgressSyncService: WatchProgressSyncService,
    private val librarySyncService: LibrarySyncService,
    private val watchedItemsSyncService: WatchedItemsSyncService,
    private val profileSettingsSyncService: ProfileSettingsSyncService,
    private val profileSyncService: ProfileSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val traktAuthDataStore: TraktAuthDataStore,
    private val traktSettingsDataStore: com.nuvio.tv.data.local.TraktSettingsDataStore,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val profileManager: ProfileManager,
    private val startupSyncPreferences: StartupSyncPreferences,
    private val cwEnrichmentCache: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastPulledIncludedProfileSettings: Boolean = false
    private var lastPulledAtMs: Long = 0L
    @Volatile
    private var forceSyncRequested: Boolean = false
    @Volatile
    private var forceSyncIncludesProfileSettings: Boolean = true
    @Volatile
    private var pendingResyncKey: String? = null
    @Volatile
    private var pendingResyncIncludesProfileSettings: Boolean = false

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.FullAccount -> {
                        val force = forceSyncRequested
                        val includeProfileSettings = if (force) forceSyncIncludesProfileSettings else true
                        val started = scheduleStartupPull(
                            userId = state.userId,
                            force = force,
                            includeProfileSettings = includeProfileSettings
                        )
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.SignedOut -> {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        lastPulledKey = null
                        lastPulledIncludedProfileSettings = false
                        lastPulledAtMs = 0L
                        forceSyncRequested = false
                        forceSyncIncludesProfileSettings = true
                        pendingResyncKey = null
                        pendingResyncIncludesProfileSettings = false
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestSyncNow(includeProfileSettings: Boolean = true) {
        forceSyncRequested = true
        forceSyncIncludesProfileSettings = forceSyncIncludesProfileSettings || includeProfileSettings
        when (val state = authManager.authState.value) {
            is AuthState.FullAccount -> {
                val started = scheduleStartupPull(
                    userId = state.userId,
                    force = true,
                    includeProfileSettings = includeProfileSettings
                )
                if (started) forceSyncRequested = false
            }
            else -> Unit
        }
    }

    fun requestForegroundSync() {
        when (val state = authManager.authState.value) {
            is AuthState.FullAccount -> {
                scheduleStartupPull(
                    userId = state.userId,
                    force = false,
                    includeProfileSettings = true,
                    allowWarmRepeat = true
                )
            }
            else -> Unit
        }
    }

    fun requestAddonSyncNow() {
        scope.launch {
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Manual addon sync requested for profile $profileId")

            addonRepository.isSyncingFromRemote = true
            try {
                val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }

                addonRepository.reconcileWithRemoteAddonUrls(
                    remoteUrls = remoteAddonUrls,
                    removeMissingLocal = true
                )

                Log.d(TAG, "Manual addon sync pulled ${remoteAddonUrls.size} addons for profile $profileId")
            } catch (e: Exception) {
                Log.e(TAG, "Manual addon sync failed", e)
            } finally {
                addonRepository.isSyncingFromRemote = false
            }
        }
    }


    private fun pullKey(userId: String): String {
        val profileId = profileManager.activeProfileId.value
        return "${userId}_p${profileId}"
    }

    private fun scheduleStartupPull(
        userId: String,
        force: Boolean = false,
        includeProfileSettings: Boolean = true,
        allowWarmRepeat: Boolean = false
    ): Boolean {
        val key = pullKey(userId)
        val now = SystemClock.elapsedRealtime()
        val sameKey = lastPulledKey == key
        val coversProfileSettings = !includeProfileSettings || lastPulledIncludedProfileSettings
        if (!force && sameKey && coversProfileSettings && !allowWarmRepeat) {
            return false
        }
        if (
            force &&
            sameKey &&
            coversProfileSettings &&
            startupPullJob?.isActive != true &&
            now - lastPulledAtMs < FORCE_RESYNC_MIN_INTERVAL_MS
        ) {
            return false
        }
        // Never cancel an active sync — it may be mid-write to DataStore.
        // Instead, schedule a follow-up sync after the current one finishes.
        if (startupPullJob?.isActive == true) {
            if (force) {
                pendingResyncKey = key
                pendingResyncIncludesProfileSettings =
                    pendingResyncIncludesProfileSettings || includeProfileSettings
            }
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            var syncCompleted = false
            for (attempt in 1..maxAttempts) {
                val result = pullRemoteData(
                    userId = userId,
                    force = force,
                    includeProfileSettings = includeProfileSettings
                )
                if (result.isSuccess) {
                    lastPulledKey = key
                    lastPulledIncludedProfileSettings = includeProfileSettings
                    lastPulledAtMs = SystemClock.elapsedRealtime()
                    syncCompleted = true
                    break
                }

                Log.w(TAG, "Startup sync attempt $attempt failed for key=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }
            
            val resyncKey = pendingResyncKey
            if (resyncKey != null) {
                val resyncIncludesProfileSettings = pendingResyncIncludesProfileSettings
                pendingResyncKey = null
                pendingResyncIncludesProfileSettings = false
                if (
                    !syncCompleted ||
                    resyncKey != lastPulledKey ||
                    (resyncIncludesProfileSettings && !lastPulledIncludedProfileSettings)
                ) {
                    scheduleStartupPull(
                        userId = userId,
                        force = true,
                        includeProfileSettings = resyncIncludesProfileSettings
                    )
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteData(
        userId: String,
        force: Boolean,
        includeProfileSettings: Boolean
    ): Result<Unit> {
        try {
            val profileId = profileManager.activeProfileId.value
            val syncState = startupSyncPreferences.getState(profileId)
            val canUseWarmSync = !force &&
                lastPulledKey == pullKey(userId) &&
                lastPulledAtMs > 0L &&
                syncState.lastFullPullUserId == userId &&
                syncState.lastFullPullAtMs > 0L &&
                System.currentTimeMillis() - syncState.lastFullPullAtMs < FULL_STARTUP_PULL_TTL_MS &&
                (!includeProfileSettings || syncState.lastFullPullIncludedProfileSettings)

            if (canUseWarmSync) {
                return pullWarmRemoteData(
                    profileId = profileId,
                    userId = userId,
                    includeProfileSettings = includeProfileSettings
                )
            }

            Log.d(TAG, "Pulling remote data for profile $profileId")
            pullBroadRemoteData(profileId, includeProfileSettings)

            val isTraktConnected = traktAuthDataStore.isEffectivelyAuthenticated.first()
            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            watchProgressSyncService.restoreLastPushTimestamp()
            watchedItemsSyncService.restoreLastPushTimestamp()
            Log.d(
                TAG,
                "Watch progress sync: isTraktConnected=$isTraktConnected shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (!isTraktConnected) {
                pullWatchedItemsDelta(profileId, traktMode = false)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = true,
                    failureMessage = "Failed to sync watch progress, continuing"
                )
            } else if (shouldUseSupabaseWatchProgressSync) {
                libraryRepository.hasCompletedInitialPull = true
                pullWatchedItemsDelta(profileId, traktMode = true)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = false,
                    failureMessage = "Failed to sync watch progress while Trakt is connected, continuing"
                )
            } else {
                libraryRepository.hasCompletedInitialPull = true
                Log.d(TAG, "Skipping Supabase watched items, watch progress, and library sync for profile $profileId because Trakt is connected and watch progress source is Trakt")
            }
            startupSyncPreferences.markFullPull(
                profileId = profileId,
                userId = userId,
                includeProfileSettings = includeProfileSettings
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            pluginManager.isSyncingFromRemote = false
            addonRepository.isSyncingFromRemote = false
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Startup sync failed", e)
            return Result.failure(e)
        }
    }

    private suspend fun pullWarmRemoteData(
        profileId: Int,
        userId: String,
        includeProfileSettings: Boolean
    ): Result<Unit> {
        try {
            Log.d(TAG, "Running warm remote sync for profile $profileId")
            pullBroadRemoteData(profileId, includeProfileSettings)
            val isTraktConnected = traktAuthDataStore.isEffectivelyAuthenticated.first()
            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            watchProgressSyncService.restoreLastPushTimestamp()
            watchedItemsSyncService.restoreLastPushTimestamp()
            Log.d(
                TAG,
                "Warm watch progress sync: isTraktConnected=$isTraktConnected shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (!isTraktConnected) {
                pullWatchedItemsDelta(profileId, traktMode = false)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = !isTraktConnected,
                    failureMessage = "Failed to sync warm watch progress, continuing"
                )
            } else if (shouldUseSupabaseWatchProgressSync) {
                libraryRepository.hasCompletedInitialPull = true
                pullWatchedItemsDelta(profileId, traktMode = true)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = false,
                    failureMessage = "Failed to sync warm watch progress while Trakt is connected, continuing"
                )
            } else {
                watchProgressRepository.hasCompletedInitialPull = true
                Log.d(TAG, "Skipping warm Supabase watch progress sync for profile $profileId because Trakt is connected and watch progress source is Trakt")
            }
            startupSyncPreferences.markFullPull(
                profileId = profileId,
                userId = userId,
                includeProfileSettings = includeProfileSettings
            )
            return Result.success(Unit)
        } catch (e: Exception) {
            watchProgressRepository.isSyncingFromRemote = false
            libraryRepository.isSyncingFromRemote = false
            Log.e(TAG, "Warm startup sync failed", e)
            return Result.failure(e)
        }
    }

    private suspend fun pullBroadRemoteData(
        profileId: Int,
        includeProfileSettings: Boolean
    ) {
        profileSyncService.pullFromRemote().getOrElse { throw it }
        Log.d(TAG, "Pulled profiles from remote")

        if (includeProfileSettings) {
            profileSettingsSyncService.pullCurrentProfileFromRemote()
                .onSuccess { applied ->
                    Log.d(TAG, "Profile settings blob pull completed for profile $profileId (applied=$applied)")
                }
                .onFailure { e ->
                    Log.e(TAG, "Failed to pull profile settings blob, keeping local settings", e)
                }
        }

        coroutineScope {
            val libraryJob = async {
                val librarySource = traktSettingsDataStore.librarySourceMode.first()
                val isTraktLibrary = librarySource == LibrarySourceMode.TRAKT &&
                    traktAuthDataStore.isEffectivelyAuthenticated.first()
                if (!isTraktLibrary) {
                    libraryRepository.isSyncingFromRemote = true
                    try {
                        val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                        Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                        libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                        libraryRepository.hasCompletedInitialPull = true
                        Log.d(TAG, "Reconciled local library with ${remoteLibraryItems.size} remote items")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to pull library, continuing with other syncs", e)
                        libraryRepository.hasCompletedInitialPull = true
                    } finally {
                        libraryRepository.isSyncingFromRemote = false
                    }
                } else {
                    libraryRepository.hasCompletedInitialPull = true
                }
            }

            val pluginJob = async {
                pluginManager.isSyncingFromRemote = true
                try {
                    val remotePlugins = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
                    pluginManager.reconcileWithRemoteRepoUrls(
                        remotePlugins = remotePlugins,
                        removeMissingLocal = true
                    )
                    Log.d(TAG, "Pulled ${remotePlugins.size} plugin repos from remote for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull plugins from remote, keeping local cache", e)
                } finally {
                    pluginManager.isSyncingFromRemote = false
                    pluginManager.flushPendingSync()
                }
            }

            val addonJob = async {
                addonRepository.isSyncingFromRemote = true
                try {
                    val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
                    addonRepository.reconcileWithRemoteAddonUrls(
                        remoteUrls = remoteAddonUrls,
                        removeMissingLocal = true
                    )
                    Log.d(TAG, "Pulled ${remoteAddonUrls.size} addons from remote for profile $profileId")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull addons from remote, keeping local cache", e)
                } finally {
                    addonRepository.isSyncingFromRemote = false
                }
            }

            val collectionJob = async {
                try {
                    collectionSyncService.pullFromRemote()
                        .onSuccess { applied ->
                            Log.d(TAG, "Collections pull completed for profile $profileId (applied=$applied)")
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Failed to pull collections from remote, keeping local", e)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull collections from remote", e)
                }
            }

            val homeCatalogJob = async {
                try {
                    homeCatalogSettingsSyncService.pullFromRemote()
                        .onSuccess { applied ->
                            Log.d(TAG, "Home catalog settings pull completed for profile $profileId (applied=$applied)")
                        }
                        .onFailure { e ->
                            Log.e(TAG, "Failed to pull home catalog settings from remote, keeping local", e)
                        }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull home catalog settings from remote", e)
                }
            }

            pluginJob.await()
            addonJob.await()
            collectionJob.await()
            homeCatalogJob.await()
            libraryJob.await()
        }
    }

    private suspend fun pullWatchedItemsDelta(
        profileId: Int,
        traktMode: Boolean
    ) {
        try {
            if (traktMode) {
                Log.d(TAG, "Starting watched items delta sync for profile $profileId while Trakt is connected")
            } else {
                Log.d(TAG, "Starting watched items delta sync for profile $profileId")
            }
            val watchedItemsResult = watchedItemsSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            if (traktMode) {
                Log.d(
                    TAG,
                    "Watched items sync applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes in Trakt mode (snapshot=${watchedItemsResult.usedSnapshot})"
                )
            } else {
                Log.d(
                    TAG,
                    "Watched items sync applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
                )
            }
            if (watchedItemsResult.preservedLocalItems) {
                if (traktMode) {
                    Log.d(TAG, "Detected unsynced watched items (Trakt mode), pushing to remote")
                } else {
                    Log.d(TAG, "Detected unsynced watched items, pushing to remote")
                }
                watchedItemsSyncService.pushToRemote()
            }
        } catch (e: Exception) {
            if (traktMode) {
                Log.e(TAG, "Failed to pull watched items, continuing with Trakt library mode", e)
            } else {
                Log.e(TAG, "Failed to pull watched items, continuing with other syncs", e)
            }
        }
    }

    private suspend fun syncWatchProgressDelta(
        profileId: Int,
        pushUnsynced: Boolean,
        failureMessage: String
    ): Result<Unit> {
        watchProgressRepository.isSyncingFromRemote = true
        try {
            val syncResult = watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialPull = true
            Log.d(
                TAG,
                "Watch progress sync applied ${syncResult.upsertedEntries} upserts and ${syncResult.deletedEntries} deletes (snapshot=${syncResult.usedSnapshot})"
            )
            // Evict deleted entries from CW enrichment cache so stale items
            // don't persist on screen via loadContinueWatching() cache restore.
            if (syncResult.deletedEntries > 0) {
                val currentContentIds = watchProgressPreferences.getAllRawEntries(profileId)
                    .values.mapTo(mutableSetOf()) { it.contentId }
                val cachedInProgress = cwEnrichmentCache.getInProgressSnapshot()
                val cachedNextUp = cwEnrichmentCache.getNextUpSnapshot()
                val filteredInProgress = cachedInProgress.filter { it.contentId in currentContentIds }
                val filteredNextUp = cachedNextUp.filter { it.contentId in currentContentIds }
                if (filteredInProgress.size != cachedInProgress.size) {
                    cwEnrichmentCache.saveInProgressSnapshot(filteredInProgress, force = true)
                }
                if (filteredNextUp.size != cachedNextUp.size) {
                    cwEnrichmentCache.saveNextUpSnapshot(filteredNextUp, force = true)
                }
            }
            if (pushUnsynced && syncResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watch progress, pushing to remote")
                watchProgressSyncService.pushToRemote(profileId)
            }
            return Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, failureMessage, e)
            return Result.failure(e)
        } finally {
            watchProgressRepository.isSyncingFromRemote = false
        }
    }
}
