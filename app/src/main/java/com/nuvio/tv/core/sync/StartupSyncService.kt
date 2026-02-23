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
    @Volatile
    private var forceSyncRequested: Boolean = false
    @Volatile
    private var pendingResyncKey: String? = null

    init {
        scope.launch {
            authManager.authState.collect { state ->
                when (state) {
                    is AuthState.FullAccount -> {
                        val force = forceSyncRequested
                        val started = scheduleStartupPull(state.userId, force = force)
                        if (force && started) forceSyncRequested = false
                    }
                    is AuthState.SignedOut -> {
                        startupPullJob?.cancel()
                        startupPullJob = null
                        lastPulledKey = null
                        forceSyncRequested = false
                        pendingResyncKey = null
                    }
                    is AuthState.Loading -> Unit
                }
            }
        }
    }

    fun requestSyncNow() {
        forceSyncRequested = true
        when (val state = authManager.authState.value) {
            is AuthState.FullAccount -> {
                val started = scheduleStartupPull(state.userId, force = true)
                if (started) forceSyncRequested = false
            }
            else -> Unit
        }
    }

    private fun pullKey(userId: String): String {
        val profileId = profileManager.activeProfileId.value
        return "${userId}_p${profileId}"
    }

    private fun scheduleStartupPull(userId: String, force: Boolean = false): Boolean {
        val key = pullKey(userId)
        if (!force && lastPulledKey == key) return false
        // Never cancel an active sync — it may be mid-write to DataStore.
        // Instead, schedule a follow-up sync after the current one finishes.
        if (startupPullJob?.isActive == true) {
            if (force) pendingResyncKey = key
            return false
        }

        startupPullJob = scope.launch {
            val maxAttempts = 3
            var syncCompleted = false
            repeat(maxAttempts) { index ->
                val attempt = index + 1
                Log.d(TAG, "Startup sync attempt $attempt/$maxAttempts for key=$key")
                val result = pullRemoteData()
                if (result.isSuccess) {
                    lastPulledKey = key
                    Log.d(TAG, "Startup sync completed for key=$key")
                    syncCompleted = true
                    return@repeat
                }

                Log.w(TAG, "Startup sync attempt $attempt failed for key=$key", result.exceptionOrNull())
                if (attempt < maxAttempts) {
                    delay(3000)
                }
            }
            if (syncCompleted) return@launch

            // After completing, check if a re-sync was requested while we were running
            val resyncKey = pendingResyncKey
            if (resyncKey != null) {
                pendingResyncKey = null
                if (resyncKey != lastPulledKey) {
                    Log.d(TAG, "Running pending re-sync for key=$resyncKey")
                    scheduleStartupPull(userId, force = true)
                }
            }
        }
        return true
    }

    private suspend fun pullRemoteData(): Result<Unit> {
        try {
            val profileId = profileManager.activeProfileId.value
            Log.d(TAG, "Pulling remote data for profile $profileId")

            // Pull profiles list first so profile selection stays up-to-date
            profileSyncService.pullFromRemote().getOrElse { throw it }
            Log.d(TAG, "Pulled profiles from remote")

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
            Log.d(TAG, "Watch progress sync: isTraktConnected=$isTraktConnected isPrimaryProfile=$isPrimaryProfile")
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
