package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"
private const val PUSH_DEBOUNCE_MS = 1500L

@Serializable
data class SyncCatalogItem(
    @SerialName("addon_id") val addonId: String,
    val type: String,
    @SerialName("catalog_id") val catalogId: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    @SerialName("custom_title") val customTitle: String = "",
    @SerialName("is_collection") val isCollection: Boolean = false,
    @SerialName("collection_id") val collectionId: String = "",
)

@Serializable
data class SyncHomeCatalogPayload(
    val items: List<SyncCatalogItem> = emptyList(),
)

@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

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

    suspend fun pushToRemote(): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val payload = layoutPreferenceDataStore.exportCatalogSettingsToSyncPayload()
            val jsonElement = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload)

            val params = buildJsonObject {
                put("p_profile_id", profileId)
                put("p_settings_json", jsonElement)
            }

            withJwtRefreshRetry {
                postgrest.rpc("sync_push_home_catalog_settings", params)
            }

            Log.d(TAG, "Pushed home catalog settings for profile $profileId")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to push home catalog settings", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value

            val params = buildJsonObject {
                put("p_profile_id", profileId)
            }

            val response = withJwtRefreshRetry {
                postgrest.rpc("sync_pull_home_catalog_settings", params)
            }
            val rows = response.decodeList<SupabaseHomeCatalogSettingsBlob>()
            val blob = rows.firstOrNull()
            if (blob == null) {
                Log.d(TAG, "No remote home catalog settings for profile $profileId")
                return@withContext Result.success(false)
            }

            val remotePayload = runCatching {
                json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), blob.settingsJson)
            }.getOrNull()

            if (remotePayload == null) {
                Log.w(TAG, "Failed to parse remote home catalog settings")
                return@withContext Result.success(false)
            }

            if (remotePayload.items.isEmpty()) {
                Log.d(TAG, "Remote has empty items, preserving local")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                layoutPreferenceDataStore.applyCatalogSettingsFromRemote(remotePayload)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Applied ${remotePayload.items.size} catalog settings from remote for profile $profileId")
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to pull home catalog settings", e)
            Result.failure(e)
        }
    }

    fun triggerPush() {
        if (isSyncingFromRemote) return
        if (!authManager.isAuthenticated) return
        pushJob?.cancel()
        pushJob = scope.launch {
            delay(500)
            pushToRemote()
        }
    }

    @OptIn(kotlinx.coroutines.FlowPreview::class)
    private fun observeLocalChangesAndPush() {
        scope.launch {
            combine(
                layoutPreferenceDataStore.homeCatalogOrderKeys,
                layoutPreferenceDataStore.disabledHomeCatalogKeys,
                layoutPreferenceDataStore.customCatalogTitles
            ) { orderKeys, disabledKeys, customTitles ->
                "${orderKeys.joinToString(",")}|${disabledKeys.joinToString(",")}|${customTitles.entries.sortedBy { it.key }.joinToString(",") { "${it.key}=${it.value}" }}"
            }
                .drop(1)
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
