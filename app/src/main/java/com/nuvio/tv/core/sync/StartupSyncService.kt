package com.nuvio.tv.core.sync

import android.os.SystemClock
import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.StartupSyncPreferences
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.domain.model.LibrarySourceMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"
private const val FORCE_RESYNC_MIN_INTERVAL_MS = 30_000L
private const val FULL_STARTUP_PULL_TTL_MS = 6 * 60 * 60 * 1000L
private const val FOREGROUND_ACTIVITY_PULL_DELAY_MS = 2_500L
private const val FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS = 2 * 60_000L
private const val PERIODIC_SURFACE_PULL_INTERVAL_MS = 15 * 60_000L

internal data class SurfacePullFreshness(
    val key: String? = null,
    val pulledAtMs: Long = 0L
) {
    fun isRecent(candidateKey: String, nowMs: Long, minIntervalMs: Long): Boolean {
        return key == candidateKey &&
            pulledAtMs > 0L &&
            nowMs >= pulledAtMs &&
            nowMs - pulledAtMs < minIntervalMs
    }
}

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
    private val providerCredentialSyncService: ProviderCredentialSyncService,
    private val profileSyncService: ProfileSyncService,
    private val pluginManager: PluginManager,
    private val addonRepository: AddonRepositoryImpl,
    private val watchProgressRepository: WatchProgressRepositoryImpl,
    private val libraryRepository: LibraryRepositoryImpl,
    private val watchProgressPreferences: WatchProgressPreferences,
    private val profileManager: ProfileManager,
    private val startupSyncPreferences: StartupSyncPreferences,
    private val cwEnrichmentCache: com.nuvio.tv.data.local.ContinueWatchingEnrichmentCache
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var activityPullJob: Job? = null
    private var periodicSurfacePullJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastPulledIncludedProfileSettings: Boolean = false
    private var lastPulledAtMs: Long = 0L
    private var activityPullFreshness = SurfacePullFreshness()
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
                        activityPullJob?.cancel()
                        activityPullJob = null
                        periodicSurfacePullJob?.cancel()
                        periodicSurfacePullJob = null
                        lastPulledKey = null
                        lastPulledIncludedProfileSettings = false
                        lastPulledAtMs = 0L
                        activityPullFreshness = SurfacePullFreshness()
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

    fun startPeriodicSurfacePulls() {
        if (periodicSurfacePullJob?.isActive == true) return
        periodicSurfacePullJob = scope.launch {
            while (true) {
                delay(PERIODIC_SURFACE_PULL_INTERVAL_MS)
                scheduleActivityPull(reason = "periodic")
            }
        }
    }

    fun stopPeriodicSurfacePulls() {
        periodicSurfacePullJob?.cancel()
        periodicSurfacePullJob = null
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
        scheduleActivityPull(
            reason = "foreground",
            delayMs = FOREGROUND_ACTIVITY_PULL_DELAY_MS,
            minIntervalMs = FOREGROUND_ACTIVITY_PULL_MIN_INTERVAL_MS
        )
    }

    private val _manualAddonRefreshes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits after a manual addon refresh, so screens holding catalogs can re-request them. */
    val manualAddonRefreshes: SharedFlow<Unit> = _manualAddonRefreshes.asSharedFlow()

    fun requestAddonSyncNow() {
        val profileId = profileManager.activeProfileId.value
        Log.d(TAG, "Manual addon sync enqueued for profile $profileId")
        scope.launch {
            Log.d(TAG, "Manual addon sync starting for profile $profileId")

            addonRepository.isSyncingFromRemote = true
            try {
                val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }

                addonRepository.reconcileWithRemoteAddonUrls(
                    remoteUrls = remoteAddonUrls,
                    removeMissingLocal = true
                )

                Log.d(TAG, "Manual addon sync pulled ${remoteAddonUrls.size} addons for profile $profileId")
            } catch (e: Exception) {
                Log.e(TAG, "Manual addon sync failed for profile $profileId", e)
            } finally {
                addonRepository.isSyncingFromRemote = false
                // The user asked for a refresh, so let screens holding catalogs re-request them
                // even when the addon list itself came back unchanged.
                _manualAddonRefreshes.tryEmit(Unit)
            }
        }
    }

    fun requestRealtimeSurfacePull(profileId: Int, surface: String) {
        if (!authManager.isAuthenticated) return
        if (surface != "profiles" && profileManager.activeProfileId.value != profileId) {
            Log.d(TAG, "Ignoring realtime surface=$surface for inactive profile $profileId")
            return
        }

        scope.launch {
            Log.i(TAG, "Realtime surface pull requested profile=$profileId surface=$surface")
            when (surface) {
                "addons" -> pullRealtimeAddons(profileId)
                "plugins" -> pullRealtimePlugins(profileId)
                "library" -> pullNuvioLibrary(profileId)
                "watch_progress" -> {
                    watchProgressSyncService.restoreLastPushTimestamp(profileId)
                    syncWatchProgressDelta(
                        profileId = profileId,
                        pushUnsynced = false,
                        failureMessage = "Realtime watch progress pull failed"
                    )
                }
                "watched_items" -> {
                    watchedItemsSyncService.restoreLastPushTimestamp(profileId)
                    if (watchProgressSyncService.shouldUseSupabaseWatchProgressSync()) {
                        pullWatchedItemsDelta(profileId = profileId, pushUnsynced = false)
                    } else {
                        watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                    }
                }
                "profile_settings" -> {
                    profileSettingsSyncService.pullCurrentProfileFromRemote()
                        .onSuccess { applied ->
                            Log.d(TAG, "Realtime profile settings pull completed profile=$profileId applied=$applied")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Realtime profile settings pull failed profile=$profileId", error)
                        }
                }
                "provider_credentials" -> {
                    providerCredentialSyncService.syncFromRemote(profileId)
                        .onSuccess { applied ->
                            Log.d(TAG, "Realtime provider credential pull completed profile=$profileId applied=$applied")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Realtime provider credential pull failed profile=$profileId", error)
                        }
                }
                "collections" -> {
                    collectionSyncService.pullFromRemote()
                        .onSuccess { applied ->
                            Log.d(TAG, "Realtime collections pull completed profile=$profileId applied=$applied")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Realtime collections pull failed profile=$profileId", error)
                        }
                }
                "home_catalog_settings" -> {
                    homeCatalogSettingsSyncService.pullFromRemote()
                        .onSuccess { applied ->
                            Log.d(TAG, "Realtime home catalog settings pull completed profile=$profileId applied=$applied")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Realtime home catalog settings pull failed profile=$profileId", error)
                        }
                }
                "profiles" -> {
                    profileSyncService.pullFromRemote(force = true)
                        .onSuccess { profiles ->
                            Log.d(TAG, "Realtime profiles pull completed count=${profiles.size}")
                        }
                        .onFailure { error ->
                            Log.e(TAG, "Realtime profiles pull failed", error)
                        }
                }
                else -> Log.w(TAG, "Unknown realtime sync surface=$surface profile=$profileId")
            }
        }
    }

    private fun scheduleActivityPull(
        reason: String,
        delayMs: Long = 0L,
        minIntervalMs: Long = 0L
    ): Boolean {
        val state = authManager.authState.value as? AuthState.FullAccount ?: return false
        val key = pullKey(state.userId)
        val now = SystemClock.elapsedRealtime()
        if (startupPullJob?.isActive == true || activityPullJob?.isActive == true) return false
        if (activityPullFreshness.isRecent(key, now, minIntervalMs)) return false

        activityPullJob = scope.launch {
            if (delayMs > 0L) delay(delayMs)
            val currentState = authManager.authState.value as? AuthState.FullAccount ?: return@launch
            if (pullKey(currentState.userId) != key || startupPullJob?.isActive == true) return@launch
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Activity sync started profile=$profileId reason=$reason")
            val succeeded = coroutineScope {
                val watchState = async { pullPeriodicWatchState() }
                val library = async { pullPeriodicLibrary() }
                watchState.await() && library.await()
            }
            if (succeeded) {
                activityPullFreshness = SurfacePullFreshness(
                    key = key,
                    pulledAtMs = SystemClock.elapsedRealtime()
                )
            }
            Log.d(TAG, "Activity sync completed profile=$profileId reason=$reason succeeded=$succeeded")
        }
        return true
    }

    private suspend fun pullPeriodicWatchState(): Boolean {
        if (authManager.authState.value !is AuthState.FullAccount) return false

        val profileId = profileManager.activeProfileId.value
        val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
        watchProgressSyncService.restoreLastPushTimestamp(profileId)
        watchedItemsSyncService.restoreLastPushTimestamp(profileId)
        Log.d(
            TAG,
            "Periodic watch state pull: profile=$profileId shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
        )

        if (shouldUseSupabaseWatchProgressSync) {
            val watchedItemsSucceeded = pullWatchedItemsDelta(profileId)
            val watchProgressSucceeded = syncWatchProgressDelta(
                profileId = profileId,
                pushUnsynced = true,
                failureMessage = "Periodic watch progress pull failed"
            ).isSuccess
            return watchedItemsSucceeded && watchProgressSucceeded
        } else {
            watchProgressRepository.hasCompletedInitialPull = true
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(TAG, "Skipping periodic Supabase watch state pull for profile $profileId because a tracking provider is active")
            return true
        }
    }

    private suspend fun pullPeriodicLibrary(): Boolean {
        if (authManager.authState.value !is AuthState.FullAccount) return false

        val profileId = profileManager.activeProfileId.value
        Log.d(TAG, "Periodic library pull requested profile=$profileId")
        return pullNuvioLibrary(profileId)
    }

    private fun pullKey(userId: String): String {
        val profileId = profileManager.activeProfileId.value
        return "${userId}_p${profileId}"
    }

    private fun scheduleStartupPull(
        userId: String,
        force: Boolean = false,
        includeProfileSettings: Boolean = true
    ): Boolean {
        val key = pullKey(userId)
        val now = SystemClock.elapsedRealtime()
        val sameKey = lastPulledKey == key
        val coversProfileSettings = !includeProfileSettings || lastPulledIncludedProfileSettings
        if (!force && sameKey && coversProfileSettings) {
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
        activityPullJob?.cancel()
        activityPullJob = null

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
                    activityPullFreshness = SurfacePullFreshness(
                        key = key,
                        pulledAtMs = lastPulledAtMs
                    )
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

            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            watchProgressSyncService.restoreLastPushTimestamp(profileId)
            watchedItemsSyncService.restoreLastPushTimestamp(profileId)
            Log.d(
                TAG,
                "Watch progress sync: shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (shouldUseSupabaseWatchProgressSync) {
                pullWatchedItemsSnapshot(profileId)
                syncWatchProgressSnapshot(
                    profileId = profileId,
                    pushUnsynced = true,
                    failureMessage = "Failed to sync watch progress, continuing"
                )
            } else {
                libraryRepository.hasCompletedInitialPull = true
                watchProgressRepository.hasCompletedInitialPull = true
                watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                Log.d(TAG, "Skipping Supabase watched items and watch progress for profile $profileId because a tracking provider is active")
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
            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            watchProgressSyncService.restoreLastPushTimestamp(profileId)
            watchedItemsSyncService.restoreLastPushTimestamp(profileId)
            Log.d(
                TAG,
                "Warm watch progress sync: shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (shouldUseSupabaseWatchProgressSync) {
                pullWatchedItemsDelta(profileId)
                syncWatchProgressDelta(
                    profileId = profileId,
                    pushUnsynced = true,
                    failureMessage = "Failed to sync warm watch progress, continuing"
                )
            } else {
                watchProgressRepository.hasCompletedInitialPull = true
                watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                Log.d(TAG, "Skipping warm Supabase watch progress sync for profile $profileId because a tracking provider is active")
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

        providerCredentialSyncService.syncFromRemote(profileId)
            .onSuccess { applied ->
                Log.d(TAG, "Provider credential sync completed for profile $profileId applied=$applied")
            }
            .onFailure { error ->
                Log.e(TAG, "Failed to sync provider credentials, keeping local credentials", error)
            }

        coroutineScope {
            val libraryJob = async {
                val isTrackingLibrary = libraryRepository.sourceMode.first() != LibrarySourceMode.LOCAL
                if (!isTrackingLibrary) {
                    libraryRepository.isSyncingFromRemote = true
                    try {
                        val result = librarySyncService.syncFromRemote(profileId).getOrElse { throw it }
                        libraryRepository.hasCompletedInitialPull = true
                        Log.d(
                            TAG,
                            "Library sync completed profile=$profileId snapshot=${result.usedSnapshot} " +
                                "upserts=${result.appliedUpserts} deletes=${result.appliedDeletes}"
                        )
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

    private suspend fun pullNuvioLibrary(profileId: Int): Boolean {
        val isTrackingLibrary = libraryRepository.sourceMode.first() != LibrarySourceMode.LOCAL
        if (isTrackingLibrary) {
            libraryRepository.hasCompletedInitialPull = true
            Log.d(TAG, "Skipping Nuvio library pull for profile $profileId because a tracking library provider is active")
            return true
        }

        libraryRepository.isSyncingFromRemote = true
        return try {
            val result = librarySyncService.syncFromRemote(profileId).getOrElse { throw it }
            libraryRepository.hasCompletedInitialPull = true
            Log.d(
                TAG,
                "Library delta pull completed profile=$profileId snapshot=${result.usedSnapshot} " +
                    "upserts=${result.appliedUpserts} deletes=${result.appliedDeletes}"
            )
            true
        } catch (e: Exception) {
            libraryRepository.hasCompletedInitialPull = true
            Log.e(TAG, "Periodic Nuvio library pull failed profile=$profileId", e)
            false
        } finally {
            libraryRepository.isSyncingFromRemote = false
        }
    }

    private suspend fun pullRealtimePlugins(profileId: Int) {
        pluginManager.isSyncingFromRemote = true
        try {
            val remotePlugins = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
            pluginManager.reconcileWithRemoteRepoUrls(
                remotePlugins = remotePlugins,
                removeMissingLocal = true
            )
            Log.d(TAG, "Realtime plugins pull reconciled ${remotePlugins.size} repos for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Realtime plugins pull failed profile=$profileId", e)
        } finally {
            pluginManager.isSyncingFromRemote = false
            pluginManager.flushPendingSync()
        }
    }

    private suspend fun pullRealtimeAddons(profileId: Int) {
        addonRepository.isSyncingFromRemote = true
        try {
            val remoteAddonUrls = addonSyncService.getRemoteAddonUrls().getOrElse { throw it }
            addonRepository.reconcileWithRemoteAddonUrls(
                remoteUrls = remoteAddonUrls,
                removeMissingLocal = true
            )
            Log.d(TAG, "Realtime addons pull reconciled ${remoteAddonUrls.size} addons for profile $profileId")
        } catch (e: Exception) {
            Log.e(TAG, "Realtime addons pull failed profile=$profileId", e)
        } finally {
            addonRepository.isSyncingFromRemote = false
        }
    }

    private suspend fun pullWatchedItemsDelta(
        profileId: Int,
        pushUnsynced: Boolean = true
    ): Boolean {
        return try {
            Log.d(TAG, "Starting watched items delta sync for profile $profileId")
            val watchedItemsResult = watchedItemsSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(
                TAG,
                "Watched items sync applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
            )
            if (pushUnsynced && watchedItemsResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watched items, pushing to remote")
                watchedItemsSyncService.pushToRemote(profileId)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items, continuing with other syncs", e)
            false
        }
    }

    private suspend fun pullWatchedItemsSnapshot(profileId: Int) {
        try {
            Log.d(TAG, "Starting watched items snapshot sync for profile $profileId")
            val watchedItemsResult = watchedItemsSyncService.syncSnapshotFromRemote(profileId).getOrElse { throw it }
            watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
            Log.d(
                TAG,
                "Watched items snapshot applied ${watchedItemsResult.upsertedItems} upserts and ${watchedItemsResult.deletedItems} deletes (snapshot=${watchedItemsResult.usedSnapshot})"
            )
            if (watchedItemsResult.preservedLocalItems) {
                Log.d(TAG, "Detected unsynced watched items after snapshot, pushing to remote")
                watchedItemsSyncService.pushToRemote(profileId)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull watched items snapshot, continuing with other syncs", e)
        }
    }

    private suspend fun syncWatchProgressDelta(
        profileId: Int,
        pushUnsynced: Boolean,
        failureMessage: String
    ): Result<Unit> {
        return syncWatchProgressRemote(
            profileId = profileId,
            pushUnsynced = pushUnsynced,
            failureMessage = failureMessage,
            useSnapshot = false
        )
    }

    private suspend fun syncWatchProgressSnapshot(
        profileId: Int,
        pushUnsynced: Boolean,
        failureMessage: String
    ): Result<Unit> {
        return syncWatchProgressRemote(
            profileId = profileId,
            pushUnsynced = pushUnsynced,
            failureMessage = failureMessage,
            useSnapshot = true
        )
    }

    private suspend fun syncWatchProgressRemote(
        profileId: Int,
        pushUnsynced: Boolean,
        failureMessage: String,
        useSnapshot: Boolean
    ): Result<Unit> {
        watchProgressRepository.isSyncingFromRemote = true
        try {
            val syncResult = if (useSnapshot) {
                watchProgressSyncService.syncSnapshotFromRemote(profileId).getOrElse { throw it }
            } else {
                watchProgressSyncService.syncDeltaFromRemote(profileId).getOrElse { throw it }
            }
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
