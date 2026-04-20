package com.omnio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.omnio.tv.core.profile.ProfileManager
import com.omnio.tv.domain.model.AioMetadataProvider
import com.omnio.tv.domain.model.AioMetadataSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

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
    private val tokenKey = stringPreferencesKey("aio_token")
    private val manifestUrlKey = stringPreferencesKey("aio_manifest_url")
    private val webUrlKey = stringPreferencesKey("aio_web_url")
    private val lastSyncedKey = longPreferencesKey("aio_last_synced_at")

    private fun providerKeyKey(provider: AioMetadataProvider) =
        stringPreferencesKey("aio_provider_key_${provider.key}")

    private fun providerEnabledKey(provider: AioMetadataProvider) =
        booleanPreferencesKey("aio_provider_enabled_${provider.key}")

    val settings: Flow<AioMetadataSettings> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs ->
                val providerKeys = AioMetadataProvider.entries.associateWith { provider ->
                    prefs[providerKeyKey(provider)].orEmpty()
                }
                val providerEnabled = AioMetadataProvider.entries.associateWith { provider ->
                    prefs[providerEnabledKey(provider)] ?: !provider.requiresApiKey
                }
                AioMetadataSettings(
                    enabled = prefs[enabledKey] ?: false,
                    token = prefs[tokenKey].orEmpty(),
                    providerEnabled = providerEnabled,
                    providerKeys = providerKeys,
                    manifestUrl = prefs[manifestUrlKey].orEmpty(),
                    webUrl = prefs[webUrlKey].orEmpty(),
                    lastSyncedAt = prefs[lastSyncedKey] ?: 0L,
                )
            }
        }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setToken(token: String) {
        store().edit { it[tokenKey] = token }
    }

    suspend fun setManifestUrl(url: String) {
        store().edit { it[manifestUrlKey] = url }
    }

    suspend fun setWebUrl(url: String) {
        store().edit { it[webUrlKey] = url }
    }

    suspend fun setProviderKey(provider: AioMetadataProvider, apiKey: String) {
        store().edit { it[providerKeyKey(provider)] = apiKey.trim() }
    }

    suspend fun setProviderEnabled(provider: AioMetadataProvider, enabled: Boolean) {
        store().edit { it[providerEnabledKey(provider)] = enabled }
    }

    suspend fun markSynced(timestampMillis: Long) {
        store().edit { it[lastSyncedKey] = timestampMillis }
    }

    /** Replace all provider keys in one write (used after remote fetch). */
    suspend fun replaceAll(settings: AioMetadataSettings) {
        store().edit { prefs ->
            prefs[enabledKey] = settings.enabled
            prefs[tokenKey] = settings.token
            prefs[manifestUrlKey] = settings.manifestUrl
            prefs[webUrlKey] = settings.webUrl
            prefs[lastSyncedKey] = settings.lastSyncedAt
            for (provider in AioMetadataProvider.entries) {
                prefs[providerKeyKey(provider)] = settings.providerKeys[provider].orEmpty()
                prefs[providerEnabledKey(provider)] =
                    settings.providerEnabled[provider] ?: !provider.requiresApiKey
            }
        }
    }
}
