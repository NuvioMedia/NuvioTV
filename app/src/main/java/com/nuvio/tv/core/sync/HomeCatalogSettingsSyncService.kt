package com.nuvio.tv.core.sync

import android.util.Log
import com.nuvio.tv.core.auth.AuthManager
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.data.local.CollectionsDataStore
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.remote.supabase.SupabaseHomeCatalogSettingsBlob
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "HomeCatalogSettingsSyncService"
private const val HOME_CATALOG_SHARED_SYNC_PLATFORM = "home_catalog_shared"
private const val TV_LEGACY_SETTINGS_SYNC_PLATFORM = "tv"
private const val PAYLOAD_SAMPLE_LIMIT = 5
private const val HIDE_UNRELEASED_CONTENT_KEY = "hide_unreleased_content"
private val HOME_CATALOG_LEGACY_SYNC_PLATFORMS = listOf(TV_LEGACY_SETTINGS_SYNC_PLATFORM, "mobile")

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
    @SerialName("hide_unreleased_content") val hideUnreleasedContent: Boolean = false,
    val items: List<SyncCatalogItem> = emptyList(),
)

private data class RemoteHomeCatalogSettings(
    val payload: SyncHomeCatalogPayload,
    val updatedAt: String?,
    val hasHideUnreleasedContent: Boolean
)

@Singleton
class HomeCatalogSettingsSyncService @Inject constructor(
    private val postgrest: Postgrest,
    private val authManager: AuthManager,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
    private val collectionsDataStore: CollectionsDataStore
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Volatile
    var isSyncingFromRemote: Boolean = false

    private var pushJob: Job? = null

    private suspend fun <T> withJwtRefreshRetry(block: suspend () -> T): T {
        return try {
            block()
        } catch (e: Exception) {
            if (!authManager.refreshSessionIfJwtExpired(e)) throw e
            block()
        }
    }

    suspend fun pushToRemote(reason: String = "unspecified"): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val payload = loadLocalPayload()
            Log.d(TAG, "Push start profile=$profileId reason=$reason ${payload.summary()}")
            pushPayload(profileId, payload)

            Log.d(TAG, "Push success profile=$profileId reason=$reason")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Push failed reason=$reason", e)
            Result.failure(e)
        }
    }

    suspend fun pullFromRemote(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val profileId = profileManager.activeProfileId.value
            val localState = layoutPreferenceDataStore.getHomeCatalogSettingsState()
            Log.d(TAG, "Pull start profile=$profileId ${localState.summary()}")

            if (localState.disabledKeys.any(::hasLegacyHomeCatalogDisabledKeyFormat)) {
                val localPayload = loadLocalPayload()
                if (localPayload.items.isNotEmpty()) {
                    isSyncingFromRemote = true
                    try {
                        layoutPreferenceDataStore.applyCatalogSettingsFromRemote(localPayload)
                    } finally {
                        isSyncingFromRemote = false
                    }
                    Log.i(TAG, "Migrated legacy local keys profile=$profileId ${localPayload.summary()} (no startup push)")
                    return@withContext Result.success(true)
                }
            }

            val localPayload = loadLocalPayload()
            val remote = fetchBestRemotePayload(profileId, localPayload)
            if (remote == null) {
                Log.d(TAG, "No remote row profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }

            val remotePayload = remote.payload
            Log.d(TAG, "Pull remote payload profile=$profileId ${remotePayload.summary()}")

            if (remotePayload.items.isEmpty()) {
                Log.d(TAG, "Remote payload empty profile=$profileId; preserving local (startup is pull-only)")
                return@withContext Result.success(false)
            }

            isSyncingFromRemote = true
            try {
                layoutPreferenceDataStore.applyCatalogSettingsFromRemote(remotePayload)
            } finally {
                isSyncingFromRemote = false
            }

            Log.d(TAG, "Pull apply success profile=$profileId ${remotePayload.summary()}")
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
            pushToRemote(reason = "triggerPush")
        }
    }

    private suspend fun loadLocalPayload(): SyncHomeCatalogPayload {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        val collections = collectionsDataStore.getCurrentCollections()
        return layoutPreferenceDataStore.exportCatalogSettingsToSyncPayload(addons, collections)
    }

    private suspend fun pushPayload(profileId: Int, payload: SyncHomeCatalogPayload) {
        val jsonElement = mergedSharedPayloadJson(profileId, payload)

        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_settings_json", jsonElement)
            put("p_platform", HOME_CATALOG_SHARED_SYNC_PLATFORM)
        }

        withJwtRefreshRetry {
            postgrest.rpc("sync_push_home_catalog_settings", params)
        }
    }

    private suspend fun fetchBestRemotePayload(
        profileId: Int,
        localPayload: SyncHomeCatalogPayload
    ): RemoteHomeCatalogSettings? {
        val shared = fetchRemotePayload(
            profileId = profileId,
            platform = HOME_CATALOG_SHARED_SYNC_PLATFORM,
            localPayload = localPayload
        )
        val legacyRows = HOME_CATALOG_LEGACY_SYNC_PLATFORMS
            .mapNotNull { platform ->
                fetchRemotePayload(
                    profileId = profileId,
                    platform = platform,
                    localPayload = localPayload
                )
            }
        val rows = listOfNotNull(shared) + legacyRows
        val selected = if (shared?.payload?.items?.isNotEmpty() == true) {
            shared
        } else {
            legacyRows
                .filter { it.payload.items.isNotEmpty() }
                .maxByOrNull { it.updatedAt.orEmpty() }
                ?: shared
                ?: legacyRows.maxByOrNull { it.updatedAt.orEmpty() }
        }

        return selected?.withNewestStandaloneSettings(rows)
    }

    private suspend fun fetchRemotePayload(
        profileId: Int,
        platform: String,
        localPayload: SyncHomeCatalogPayload
    ): RemoteHomeCatalogSettings? {
        val blob = fetchRemoteBlob(profileId, platform) ?: return null
        val payload = decodePayloadPreservingLocalDefaults(blob.settingsJson, localPayload)
        if (payload == null) {
            Log.w(TAG, "Pull parse failure profile=$profileId platform=$platform")
            return null
        }
        return RemoteHomeCatalogSettings(
            payload = payload,
            updatedAt = blob.updatedAt,
            hasHideUnreleasedContent = blob.settingsJson.containsKey(HIDE_UNRELEASED_CONTENT_KEY)
        )
    }

    private fun RemoteHomeCatalogSettings.withNewestStandaloneSettings(
        rows: List<RemoteHomeCatalogSettings>
    ): RemoteHomeCatalogSettings {
        val hideUnreleasedSource = rows
            .filter { it.hasHideUnreleasedContent }
            .maxByOrNull { it.updatedAt.orEmpty() }

        return copy(
            payload = payload.copy(
                hideUnreleasedContent = hideUnreleasedSource?.payload?.hideUnreleasedContent
                    ?: payload.hideUnreleasedContent
            )
        )
    }

    private suspend fun fetchRemoteBlob(
        profileId: Int,
        platform: String
    ): SupabaseHomeCatalogSettingsBlob? {
        val params = buildJsonObject {
            put("p_profile_id", profileId)
            put("p_platform", platform)
        }
        val response = withJwtRefreshRetry {
            postgrest.rpc("sync_pull_home_catalog_settings", params)
        }
        return response.decodeList<SupabaseHomeCatalogSettingsBlob>().firstOrNull()
    }

    private fun decodePayloadPreservingLocalDefaults(
        settingsJson: JsonObject,
        localPayload: SyncHomeCatalogPayload
    ): SyncHomeCatalogPayload? = runCatching {
        val decoded = json.decodeFromJsonElement(SyncHomeCatalogPayload.serializer(), settingsJson)
        decoded.copy(
            hideUnreleasedContent = if (settingsJson.containsKey(HIDE_UNRELEASED_CONTENT_KEY)) {
                decoded.hideUnreleasedContent
            } else {
                localPayload.hideUnreleasedContent
            }
        )
    }.getOrNull()

    private suspend fun mergedSharedPayloadJson(
        profileId: Int,
        payload: SyncHomeCatalogPayload
    ): JsonObject {
        val localJson = json.encodeToJsonElement(SyncHomeCatalogPayload.serializer(), payload).jsonObject
        val remoteJson = fetchRemoteBlob(profileId, HOME_CATALOG_SHARED_SYNC_PLATFORM)?.settingsJson
        return buildJsonObject {
            remoteJson?.forEach { (key, value) -> put(key, value) }
            localJson.forEach { (key, value) -> put(key, value) }
        }
    }

}

private fun LocalHomeCatalogSettingsState.summary(): String {
    val orderSample = orderKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val disabledSample = disabledKeys.take(PAYLOAD_SAMPLE_LIMIT)
    val titleSample = customTitles.keys.take(PAYLOAD_SAMPLE_LIMIT)
    return "localState(order=${orderKeys.size}, disabled=${disabledKeys.size}, titles=${customTitles.size}, orderSample=$orderSample, disabledSample=$disabledSample, titleSample=$titleSample)"
}

private fun SyncHomeCatalogPayload.summary(): String {
    val disabledCount = items.count { !it.enabled }
    val collectionCount = items.count { it.isCollection }
    val sample = items.take(PAYLOAD_SAMPLE_LIMIT).joinToString(separator = " | ") { item ->
        if (item.isCollection) {
            "collection:${item.collectionId},enabled=${item.enabled},order=${item.order}"
        } else {
            "catalog:${item.addonId}/${item.type}/${item.catalogId},enabled=${item.enabled},order=${item.order}"
        }
    }
    return "payload(items=${items.size}, disabled=$disabledCount, collections=$collectionCount, hideUnreleased=$hideUnreleasedContent, sample=[$sample])"
}
