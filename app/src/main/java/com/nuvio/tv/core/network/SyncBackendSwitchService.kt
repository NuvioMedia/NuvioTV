package com.nuvio.tv.core.network

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.data.remote.supabase.AvatarRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val SYNC_BACKEND_SWITCH_TAG = "SyncBackendSwitch"

@Singleton
class SyncBackendSwitchService @Inject constructor(
    private val syncBackendRepository: SyncBackendRepository,
    private val supabaseProvider: SyncBackendSupabaseProvider,
    private val authManager: AuthManager,
    private val avatarRepository: AvatarRepository,
) {
    private val refreshMutex = Mutex()

    suspend fun refreshSelection() = refreshMutex.withLock {
        syncBackendRepository.ensureLoaded()
        when (val result = syncBackendRepository.refreshFromManifest()) {
            SyncBackendRefreshResult.NotConfigured,
            is SyncBackendRefreshResult.Failed,
            SyncBackendRefreshResult.Unchanged -> Unit
            is SyncBackendRefreshResult.Applied -> {
                supabaseProvider.rebuildClient()
                avatarRepository.invalidateCache()
            }
            is SyncBackendRefreshResult.RequiresLogout -> {
                authManager.resetForSyncBackendChange()
                    .onSuccess {
                        syncBackendRepository.applyBackendAfterLogout(result.targetBackend, result.revision)
                        supabaseProvider.rebuildClient()
                        avatarRepository.invalidateCache()
                    }
                    .onFailure { error ->
                        Log.w(SYNC_BACKEND_SWITCH_TAG, "Failed to reset auth for sync backend change", error)
                    }
            }
        }
    }
}
