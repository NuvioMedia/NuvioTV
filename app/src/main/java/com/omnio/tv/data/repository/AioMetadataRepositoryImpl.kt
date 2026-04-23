package com.omnio.tv.data.repository

import android.util.Log
import com.omnio.tv.BuildConfig
import com.omnio.tv.core.auth.AuthManager
import com.omnio.tv.core.profile.ProfileManager
import com.omnio.tv.data.local.AioMetadataSettingsDataStore
import com.omnio.tv.data.remote.api.AioMetadataApi
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigInnerDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigLoadRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigSaveRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigUpdateRequestDto
import com.omnio.tv.domain.model.AioMetadataSettings
import com.omnio.tv.domain.repository.AddonRepository
import com.omnio.tv.domain.repository.AioMetadataRepository
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.security.SecureRandom
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AioMetadataRepository"
private const val TABLE = "aio_metadata_links"

/**
 * Coordinates the three sources of truth for AIOMetadata:
 *  - upstream (cedya77/aiometadata): API keys + catalog config
 *  - Supabase aio_metadata_links: user_id → aio_uuid + config_password + manifest_url + enabled
 *  - local DataStore: fast-path cache so the settings screen renders without a round-trip
 *
 * Upstream gates every save/update/load on a per-user password we mint on first
 * save (see getOrCreatePassword). Supabase is authoritative across devices;
 * DataStore is just a warm cache.
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

    override val settings: Flow<AioMetadataSettings> = dataStore.settings

    @Volatile
    private var currentConfig: AioConfigInnerDto? = null

    override fun cachedConfig(): AioConfigInnerDto? = currentConfig

    override suspend fun refresh(): Result<AioConfigInnerDto?> = runCatching {
        val link = fetchLink() ?: run {
            currentConfig = null
            return@runCatching null
        }

        // Mirror Supabase password into the local DataStore so updateConfig and
        // subsequent loads can run without another round-trip.
        val password = link.configPassword?.takeIf { it.isNotBlank() }
            ?: dataStore.getConfigPassword()?.takeIf { it.isNotBlank() }
        if (password == null) {
            Log.w(TAG, "link row missing config_password for uuid=${link.aioUuid}; cannot load upstream config")
            currentConfig = null
            return@runCatching null
        }
        if (link.configPassword != null) dataStore.setConfigPassword(password)

        val response = api.loadConfig(link.aioUuid, AioConfigLoadRequestDto(password = password))
        if (!response.isSuccessful) {
            error("loadConfig failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("loadConfig empty body")
        val inner = body.config
        currentConfig = inner

        dataStore.replaceAll(
            AioMetadataSettings(
                enabled = link.enabled,
                aioUuid = link.aioUuid,
                manifestUrl = link.manifestUrl.orEmpty(),
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
        inner
    }.onFailure { Log.w(TAG, "refresh failed", it) }

    override suspend fun createConfig(config: AioConfigInnerDto): Result<AioMetadataRepository.CreateConfigResult> = runCatching {
        val password = getOrCreatePassword()
        val response = api.saveConfig(
            AioConfigSaveRequestDto(config = config, password = password, addonPassword = null)
        )
        if (!response.isSuccessful) {
            error("saveConfig failed: HTTP ${response.code()}")
        }
        val body = response.body() ?: error("saveConfig empty body")
        currentConfig = config

        val manifestUrl = body.installUrl ?: buildFallbackManifestUrl(body.userUUID)
        upsertLink(
            aioUuid = body.userUUID,
            manifestUrl = manifestUrl,
            enabled = false,
            configPassword = password,
        )
        dataStore.replaceAll(
            AioMetadataSettings(
                enabled = false,
                aioUuid = body.userUUID,
                manifestUrl = manifestUrl,
                lastSyncedAt = System.currentTimeMillis(),
            )
        )
        AioMetadataRepository.CreateConfigResult(uuid = body.userUUID, manifestUrl = manifestUrl)
    }.onFailure { Log.w(TAG, "createConfig failed", it) }

    override suspend fun updateConfig(uuid: String, config: AioConfigInnerDto): Result<AioConfigInnerDto> =
        runCatching {
            val password = dataStore.getConfigPassword()?.takeIf { it.isNotBlank() }
                ?: fetchLink()?.configPassword?.takeIf { it.isNotBlank() }
                ?: error("No AIOMetadata password stored; re-create the config.")

            val response = api.updateConfig(
                uuid = uuid,
                body = AioConfigUpdateRequestDto(config = config, password = password, addonPassword = null),
            )
            if (!response.isSuccessful) {
                error("updateConfig failed: HTTP ${response.code()}")
            }
            val body = response.body() ?: error("updateConfig empty body")
            currentConfig = config

            body.installUrl?.takeIf { it.isNotBlank() }?.let { dataStore.setManifestUrl(it) }
            dataStore.markSynced(System.currentTimeMillis())

            // Refresh the bridge row so manifestUrl stays in sync across devices.
            body.installUrl?.takeIf { it.isNotBlank() }?.let { freshManifest ->
                fetchLink()?.let { link ->
                    if (link.manifestUrl != freshManifest) {
                        upsertLink(
                            aioUuid = link.aioUuid,
                            manifestUrl = freshManifest,
                            enabled = link.enabled,
                            configPassword = password,
                        )
                    }
                }
            }
            config
        }.onFailure { Log.w(TAG, "updateConfig failed", it) }

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

    // --- Password -----------------------------------------------------------

    private suspend fun getOrCreatePassword(): String {
        fetchLink()?.configPassword?.takeIf { it.isNotBlank() }?.let { fromSupabase ->
            dataStore.setConfigPassword(fromSupabase)
            return fromSupabase
        }
        dataStore.getConfigPassword()?.takeIf { it.isNotBlank() }?.let { return it }
        val fresh = generatePassword()
        dataStore.setConfigPassword(fresh)
        return fresh
    }

    private fun generatePassword(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // --- Supabase helpers ----------------------------------------------------

    @Serializable
    private data class LinkRow(
        @SerialName("user_id") val userId: String,
        @SerialName("aio_uuid") val aioUuid: String,
        val enabled: Boolean,
        @SerialName("manifest_url") val manifestUrl: String? = null,
        @SerialName("config_password") val configPassword: String? = null,
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

    private suspend fun upsertLink(
        aioUuid: String,
        manifestUrl: String,
        enabled: Boolean,
        configPassword: String?,
    ) {
        val userId = authManager.getEffectiveUserId() ?: error("Not authenticated")
        postgrest.from(TABLE).upsert(
            LinkRow(
                userId = userId,
                aioUuid = aioUuid,
                enabled = enabled,
                manifestUrl = manifestUrl,
                configPassword = configPassword,
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
                configPassword = existing.configPassword,
            )
        )
    }

    private fun buildFallbackManifestUrl(uuid: String): String {
        val base = BuildConfig.AIOMETADATA_BASE_URL.trimEnd('/')
        if (base.isBlank()) return ""
        return "$base/api/manifest/$uuid/manifest.json"
    }
}
