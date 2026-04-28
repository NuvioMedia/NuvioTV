package com.omnio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.omnio.tv.domain.profile.ProfileManager
import com.omnio.tv.domain.model.AioMetadataSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-profile cache of the AIOMetadata bridge-table row. We only persist the
 * UUID, manifest URL, and enable flag — provider keys live upstream.
 */
@Singleton
class AioMetadataSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager,
) {
    companion object {
        private const val FEATURE = "aio_metadata_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val enabledKey = booleanPreferencesKey("aio_enabled")
    private val uuidKey = stringPreferencesKey("aio_uuid")
    private val manifestUrlKey = stringPreferencesKey("aio_manifest_url")
    private val lastSyncedKey = longPreferencesKey("aio_last_synced_at")
    private val configPasswordKey = stringPreferencesKey("aio_config_password")

    val settings: Flow<AioMetadataSettings> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                AioMetadataSettings(
                    enabled = prefs[enabledKey] ?: false,
                    aioUuid = prefs[uuidKey].orEmpty(),
                    manifestUrl = prefs[manifestUrlKey].orEmpty(),
                    lastSyncedAt = prefs[lastSyncedKey] ?: 0L,
                )
            }
        }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setUuid(uuid: String) {
        store().edit { it[uuidKey] = uuid }
    }

    suspend fun setManifestUrl(url: String) {
        store().edit { it[manifestUrlKey] = url }
    }

    suspend fun markSynced(timestampMillis: Long) {
        store().edit { it[lastSyncedKey] = timestampMillis }
    }

    suspend fun getConfigPassword(): String? =
        store().data.first()[configPasswordKey]

    suspend fun setConfigPassword(password: String?) {
        store().edit { prefs ->
            if (password.isNullOrBlank()) prefs.remove(configPasswordKey)
            else prefs[configPasswordKey] = password
        }
    }

    suspend fun replaceAll(settings: AioMetadataSettings) {
        store().edit { prefs ->
            prefs[enabledKey] = settings.enabled
            prefs[uuidKey] = settings.aioUuid
            prefs[manifestUrlKey] = settings.manifestUrl
            prefs[lastSyncedKey] = settings.lastSyncedAt
        }
    }

    suspend fun clear() {
        store().edit { it.clear() }
    }

    /**
     * Seeds an entire AIO settings record for a specific profile (used when
     * provisioning a separate Kids-profile AIO config from a different
     * profile's context).
     */
    suspend fun seedForProfile(
        profileId: Int,
        settings: AioMetadataSettings,
        configPassword: String,
    ) {
        store(profileId).edit { prefs ->
            prefs[enabledKey] = settings.enabled
            prefs[uuidKey] = settings.aioUuid
            prefs[manifestUrlKey] = settings.manifestUrl
            prefs[lastSyncedKey] = settings.lastSyncedAt
            prefs[configPasswordKey] = configPassword
        }
    }
}
