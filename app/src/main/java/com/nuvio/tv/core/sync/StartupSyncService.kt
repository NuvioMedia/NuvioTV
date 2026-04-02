package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LibraryPreferences
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.WatchProgressPreferences
import com.nuvio.tv.data.local.WatchedItemsPreferences
import com.nuvio.tv.data.repository.AddonRepositoryImpl
import com.nuvio.tv.data.repository.LibraryRepositoryImpl
import com.nuvio.tv.data.repository.WatchProgressRepositoryImpl
import com.nuvio.tv.domain.model.AuthState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "StartupSyncService"

@Singleton
class StartupSyncService @Inject constructor(
    private val authManager: AuthManager,
    private val pluginSyncService: PluginSyncService,
    private val addonSyncService: AddonSyncService,
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
    private val watchProgressPreferences: WatchProgressPreferences,
    private val libraryPreferences: LibraryPreferences,
    private val watchedItemsPreferences: WatchedItemsPreferences,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var startupPullJob: Job? = null
    private var lastPulledKey: String? = null
    private var lastPulledIncludedProfileSettings: Boolean = false
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
        if (
            !force &&
            lastPulledKey == key &&
            (!includeProfileSettings || lastPulledIncludedProfileSettings)
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
                val result = pullRemoteData(includeProfileSettings = includeProfileSettings)
                if (result.isSuccess) {
                    lastPulledKey = key
                    lastPulledIncludedProfileSettings = includeProfileSettings
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

    private suspend fun pullRemoteData(includeProfileSettings: Boolean): Result<Unit> {
        try {
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Pulling remote data for profile $profileId")

            // Pull profiles list first so profile selection stays up-to-date
            profileSyncService.pullFromRemote().getOrElse { throw it }
            Log.d(TAG, "Pulled profiles from remote")

            if (includeProfileSettings) {
                // Pull profile-scoped UI/player/settings blob.
                // If not present, local settings are preserved.
                profileSettingsSyncService.pullCurrentProfileFromRemote()
                    .onSuccess { applied ->
                        Log.d(TAG, "Profile settings blob pull completed for profile $profileId (applied=$applied)")
                    }
                    .onFailure { e ->
                        Log.e(TAG, "Failed to pull profile settings blob, keeping local settings", e)
                    }
            }

            pluginManager.isSyncingFromRemote = true
            try {
                val remotePluginUrls = pluginSyncService.getRemoteRepoUrls().getOrElse { throw it }
                pluginManager.reconcileWithRemoteRepoUrls(
                    remoteUrls = remotePluginUrls,
                    removeMissingLocal = true
                )
                Log.d(TAG, "Pulled ${remotePluginUrls.size} plugin repos from remote for profile $profileId")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to pull plugins from remote, keeping local cache", e)
            } finally {
                pluginManager.isSyncingFromRemote = false
            }

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

            val isPrimaryProfile = profileManager.activeProfileId.value == 1
            val isTraktConnected = isPrimaryProfile && traktAuthDataStore.isAuthenticated.first()
            val shouldUseSupabaseWatchProgressSync = watchProgressSyncService.shouldUseSupabaseWatchProgressSync()
            Log.d(
                TAG,
                "Watch progress sync: isTraktConnected=$isTraktConnected isPrimaryProfile=$isPrimaryProfile shouldUseSupabaseWatchProgressSync=$shouldUseSupabaseWatchProgressSync"
            )
            if (!isTraktConnected) {
                // Pull library and watched items first — these are lightweight and critical.
                // Watch progress is pulled last because the table is large and may time out;
                // a failure there must not block the other syncs.

                libraryRepository.isSyncingFromRemote = true
                try {
                    val remoteLibraryItems = librarySyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteLibraryItems.size} library items from remote")
                    libraryPreferences.mergeRemoteItems(remoteLibraryItems)
                    libraryRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Reconciled local library with ${remoteLibraryItems.size} remote items")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull library, continuing with other syncs", e)
                } finally {
                    libraryRepository.isSyncingFromRemote = false
                }

                try {
                    val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteWatchedItems.size} watched items from remote")
                    watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                    watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                    Log.d(TAG, "Reconciled local watched items with ${remoteWatchedItems.size} remote items")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull watched items, continuing with other syncs", e)
                }

                watchProgressRepository.isSyncingFromRemote = true
                try {
                    val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteEntries.size} watch progress entries from remote")
                    watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                    watchProgressRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Merged local watch progress with ${remoteEntries.size} remote entries")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull watch progress, continuing", e)
                } finally {
                    watchProgressRepository.isSyncingFromRemote = false
                }
            } else if (shouldUseSupabaseWatchProgressSync) {
                try {
                    val remoteWatchedItems = watchedItemsSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteWatchedItems.size} watched items from remote")
                    watchedItemsPreferences.replaceWithRemoteItems(remoteWatchedItems)
                    watchProgressRepository.hasCompletedInitialWatchedItemsPull = true
                    Log.d(TAG, "Reconciled local watched items with ${remoteWatchedItems.size} remote items")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull watched items, continuing with Trakt library mode", e)
                }

                watchProgressRepository.isSyncingFromRemote = true
                try {
                    val remoteEntries = watchProgressSyncService.pullFromRemote().getOrElse { throw it }
                    Log.d(TAG, "Pulled ${remoteEntries.size} watch progress entries from remote")
                    watchProgressPreferences.mergeRemoteEntries(remoteEntries.toMap())
                    watchProgressRepository.hasCompletedInitialPull = true
                    Log.d(TAG, "Merged local watch progress with ${remoteEntries.size} remote entries")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to pull watch progress while Trakt is connected, continuing", e)
                } finally {
                    watchProgressRepository.isSyncingFromRemote = false
                }
            } else {
                Log.d(TAG, "Skipping watch progress & library sync (Trakt connected)")
            }
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
}
