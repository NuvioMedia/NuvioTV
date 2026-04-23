package com.omnio.tv.data.repository

import android.util.Log
import com.omnio.tv.BuildConfig
import com.omnio.tv.core.auth.AuthManager
import com.omnio.tv.core.profile.ProfileManager
import com.omnio.tv.data.local.AioMetadataSettingsDataStore
import com.omnio.tv.data.remote.api.AioMetadataApi
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigResponseDto
import com.omnio.tv.domain.model.AioMetadataSettings
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.AioMetadataRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AioMetadataRepository"
private const val TABLE = "aio_metadata_links"

/**
 * Coordinates the three sources of truth for AIOMetadata:
 *  - upstream (cedya77/aiometadata): provider keys + catalog config
 *  - Supabase aio_metadata_links: mapping user_id → aio_uuid, last-known manifest_url, enable flag
 *  - local DataStore: fast-path cache so the settings screen renders without a round-trip
 */
@Singleton
class AioMetadataRepositoryImpl @Inject constructor(
    private val api: AioMetadataApi,
    private val postgrest: Postgrest,
    private val dataStore: AioMetadataSettingsDataStore,
    private val authManager: AuthManager,
    private val profileManager: ProfileManager,
    private val addonRepository: AddonRepository,
) : AioMetadataRepository {

    /** Local cache of the bridge-table row; drives the settings screen. */
    override val settings: Flow<AioMetadataSettings> = dataStore.settings

    /**
     * Full config loaded from upstream; kept in-memory so the UI layer can edit
     * it without stashing provider keys on disk.
     */
    @Volatile
    private var currentConfig: AioConfigResponseDto? = null

    override fun cachedConfig(): AioConfigResponseDto? = currentConfig

    /**
     * Pull the bridge-table row (if any), then fetch the upstream config. Safe
     * to call on screen-open; a null result means the user hasn't created a
     * config yet.
     */
    override suspend fun refresh(): Result<AioConfigResponseDto?> = runCatching {
        val link = fetchLink() ?: run {
            currentConfig = null
            return@runCatching null
        }

        val response = api.loadConfig(link.aioUuid)
        if (!response.isSuccessful) {
            error("loadConfig failed: HTTP ${response.code()}")
        }
        val config = response.body() ?: error("loadConfig empty body")
        currentConfig = config

        dataStore.replaceAll(
            AioMetadataSettings(
                enabled = link.enabled,
                aioUuid = config.uuid,
                manifestUrl = config.manifestUrl.orEmpty(),
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
        config
    }.onFailure { Log.w(TAG, "refresh failed", it) }

    /**
     * First-time creation: POST /api/config/save, then write the bridge row.
     * Returns the UUID upstream minted.
     */
    override suspend fun createConfig(request: AioConfigRequestDto): Result<String> = runCatching {
        val response = api.saveConfig(request)
        if (!response.isSuccessful) {
            error("saveConfig failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("saveConfig empty body")
        currentConfig = body

        val manifestUrl = body.manifestUrl ?: buildFallbackManifestUrl(body.uuid)
        upsertLink(body.uuid, manifestUrl, enabled = false)
        dataStore.replaceAll(
            AioMetadataSettings(
                enabled = false,
                aioUuid = body.uuid,
                manifestUrl = manifestUrl,
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
        body.uuid
    }.onFailure { Log.w(TAG, "createConfig failed", it) }

    /**
     * Update existing config. Caller supplies the full request (upstream treats
     * /update as a full replace at the time of writing; keep a merged payload
     * in-memory via [cachedConfig] to avoid wiping fields).
     */
    override suspend fun updateConfig(uuid: String, request: AioConfigRequestDto): Result<AioConfigResponseDto> =
        runCatching {
            val response = api.updateConfig(uuid, request)
            if (!response.isSuccessful) {
                error("updateConfig failed: HTTP ${response.code()}")
            }
            val body = response.body() ?: error("updateConfig empty body")
            currentConfig = body
            body.manifestUrl?.let { dataStore.setManifestUrl(it) }
            dataStore.markSynced(System.currentTimeMillis())
            body
        }.onFailure { Log.w(TAG, "updateConfig failed", it) }

    /**
     * Toggle. Writes to Supabase and DataStore, and installs or removes the
     * addon URL on the active profile. Returns [Failure] if the active profile
     * uses primary-only addons.
     */
    override suspend fun setEnabled(enabled: Boolean, manifestUrl: String): Result<Unit> = runCatching {
        val profile = profileManager.activeProfile
        if (profile?.usesPrimaryAddons == true) {
            error("Active profile uses primary addons; switch profiles to manage AIOMetadata.")
        }

        dataStore.setEnabled(enabled)
        upsertLinkEnabled(enabled)

        if (manifestUrl.isNotBlank()) {
            if (enabled) addonRepository.addAddon(manifestUrl)
            else addonRepository.removeAddon(manifestUrl)
        }
    }.onFailure { Log.w(TAG, "setEnabled failed", it) }

    // --- Supabase helpers ----------------------------------------------------

    @Serializable
    private data class LinkRow(
        @SerialName("user_id") val userId: String,
        @SerialName("aio_uuid") val aioUuid: String,
        val enabled: Boolean,
        @SerialName("manifest_url") val manifestUrl: String? = null,
    )

    private suspend fun fetchLink(): LinkRow? {
        val userId = authManager.getEffectiveUserId() ?: return null
        return try {
            postgrest.from(TABLE)
                .select {
                    filter { eq("user_id", userId) }
                    limit(1)
                }
                .decodeList<LinkRow>()
                .firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "fetchLink failed", e)
            null
        }
    }

    private suspend fun upsertLink(aioUuid: String, manifestUrl: String, enabled: Boolean) {
        val userId = authManager.getEffectiveUserId() ?: error("Not authenticated")
        postgrest.from(TABLE).upsert(
            LinkRow(
                userId = userId,
                aioUuid = aioUuid,
                enabled = enabled,
                manifestUrl = manifestUrl,
            )
        )
    }

    private suspend fun upsertLinkEnabled(enabled: Boolean) {
        val userId = authManager.getEffectiveUserId() ?: error("Not authenticated")
        val existing = fetchLink() ?: error("No AIOMetadata link row to update")
        postgrest.from(TABLE).upsert(
            LinkRow(
                userId = userId,
                aioUuid = existing.aioUuid,
                enabled = enabled,
                manifestUrl = existing.manifestUrl,
            )
        )
    }

    private fun buildFallbackManifestUrl(uuid: String): String {
        val base = BuildConfig.AIOMETADATA_BASE_URL.trimEnd('/')
        if (base.isBlank()) return ""
        // Upstream's manifest path shape is /stremio/{uuid}/{compressedConfig}/manifest.json,
        // but when upstream does not return manifestUrl we fall back to the
        // legacy /api/manifest/{uuid} shape some builds expose. If upstream
        // stops accepting that, saveConfig's response will still carry a URL.
        return "$base/api/manifest/$uuid/manifest.json"
    }
}
