package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseCollectionBlob
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "CollectionSyncService"
private const val PUSH_DEBOUNCE_MS = 1500L

@Singleton
class CollectionSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val collectionsDataStore: CollectionsDataStore,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    init {
        observeLocalChangesAndPush()
    }

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    /**
     * Push local collections JSON to Supabase via RPC.
     * Uses a SECURITY DEFINER function to handle RLS for linked devices.
     */
    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val json = collectionsDataStore.exportCurrentProfileJson()

            val collectionsJsonElement = if (json.isNullOrBlank()) {
                JsonArray(emptyList())
            } else {
                Json.parseToJsonElement(json)
            }

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_collections_json", collectionsJsonElement)
            }

            withJwtRefreshRetry {
                postgrest.rpc("sync_push_collections", params)
            }

            Log.d(TAG, "Pushed collections to remote for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push collections to remote", e)
            Result.failure(e)
        }
    }

    /**
     * Pull remote collections JSON and apply locally.
     * Returns true if local state was updated.
     */
    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value

            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }

            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_collections", params)
            }
            val rows = response.decodeList<SupabaseCollectionBlob>()
            val blob = rows.firstOrNull()
            if (blob == null) {
                Log.d(TAG, "No remote collections for profile $profileId; keeping local")
                return@withContext Result.success(false)
            }

            val remoteJson = blob.collectionsJson.toString()
            val remoteCollections = collectionsDataStore.importFromJson(remoteJson)

            // Preserve local if remote is empty but local has data
            val localCollections = collectionsDataStore.getCurrentCollections()
            if (remoteCollections.isEmpty() && localCollections.isNotEmpty()) {
                Log.w(TAG, "Remote collections empty while local has ${localCollections.size}; preserving local")
                return@withContext Result.success(false)
            }

            // Check if different
            val localJson = collectionsDataStore.exportCurrentProfileJson() ?: ""
            if (remoteJson == localJson) {
                Log.d(TAG, "Remote collections already match local for profile $profileId")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                collectionsDataStore.setCollections(remoteCollections)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Applied ${remoteCollections.size} remote collections for profile $profileId")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull collections from remote", e)
            Result.failure(e)
        }
    }

    /**
     * Trigger a debounced push to remote after a local change.
     */
    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }

    /**
     * Observe local collections DataStore changes and auto-push to remote.
     */
    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        scope.launch {
            collectionsDataStore.collections
                .drop(1) // skip initial value
                .distinctUntilChanged()
                .debounce(PUSH_DEBOUNCE_MS)
                .collect {
                    if (!authManager.isAuthenticated) return@collect
                    if (isSyncingFromRemote) return@collect
                    pushToRemote()
                }
        }
    }
}
