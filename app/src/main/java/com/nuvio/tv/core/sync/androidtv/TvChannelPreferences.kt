package com.nuvio.tv.core.sync.androidtv

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tvChannelDataStore by preferencesDataStore(name = "tv_channel_prefs")

@Singleton
class TvChannelPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /**
     * Keep the original Continue Watching preference untouched so existing
     * installations don't lose their current Android TV Home channel.
     */
    private val continueWatchingChannelIdKey =
        longPreferencesKey("continue_watching_channel_id")

    /**
     * Catalogs explicitly selected by the user for Android TV Home.
     */
    private val enabledCatalogKeysKey =
        stringSetPreferencesKey("enabled_tv_catalog_keys")

    /**
     * Existing Continue Watching API.
     * Kept for backwards compatibility with AndroidTvChannelManager.
     */
    suspend fun getChannelId(): Long? =
        context.tvChannelDataStore.data
            .map { it[continueWatchingChannelIdKey] }
            .first()

    suspend fun setChannelId(id: Long) {
        context.tvChannelDataStore.edit {
            it[continueWatchingChannelIdKey] = id
        }
    }

    suspend fun clearChannelId() {
        context.tvChannelDataStore.edit {
            it.remove(continueWatchingChannelIdKey)
        }
    }

    /**
     * Returns the Android TV channel ID belonging to a specific catalog.
     *
     * The real catalog key may contain URLs or other characters, therefore
     * the preference key uses a stable SHA-256 hash.
     */
    suspend fun getCatalogChannelId(catalogKey: String): Long? =
        context.tvChannelDataStore.data
            .map { prefs -> prefs[catalogChannelIdKey(catalogKey)] }
            .first()

    suspend fun setCatalogChannelId(catalogKey: String, channelId: Long) {
        context.tvChannelDataStore.edit { prefs ->
            prefs[catalogChannelIdKey(catalogKey)] = channelId
        }
    }

    suspend fun clearCatalogChannelId(catalogKey: String) {
        context.tvChannelDataStore.edit { prefs ->
            prefs.remove(catalogChannelIdKey(catalogKey))
        }
    }

    /**
     * Catalogs that should be published as individual rows/channels
     * on the Android TV launcher.
     */
    val enabledCatalogKeys: Flow<Set<String>> =
        context.tvChannelDataStore.data.map { prefs ->
            prefs[enabledCatalogKeysKey].orEmpty()
        }

    suspend fun getEnabledCatalogKeys(): Set<String> =
        enabledCatalogKeys.first()

    suspend fun isCatalogEnabled(catalogKey: String): Boolean =
        catalogKey in getEnabledCatalogKeys()

    suspend fun setCatalogEnabled(catalogKey: String, enabled: Boolean) {
        context.tvChannelDataStore.edit { prefs ->
            val current = prefs[enabledCatalogKeysKey]
                .orEmpty()
                .toMutableSet()

            if (enabled) {
                current.add(catalogKey)
            } else {
                current.remove(catalogKey)
            }

            if (current.isEmpty()) {
                prefs.remove(enabledCatalogKeysKey)
            } else {
                prefs[enabledCatalogKeysKey] = current
            }
        }
    }

    suspend fun setEnabledCatalogKeys(catalogKeys: Set<String>) {
        context.tvChannelDataStore.edit { prefs ->
            if (catalogKeys.isEmpty()) {
                prefs.remove(enabledCatalogKeysKey)
            } else {
                prefs[enabledCatalogKeysKey] = catalogKeys.toSet()
            }
        }
    }

    private fun catalogChannelIdKey(catalogKey: String) =
        longPreferencesKey(
            "catalog_channel_id_${sha256(catalogKey)}"
        )

    private fun sha256(value: String): String {
        return MessageDigest
            .getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString(separator = "") { byte ->
                "%02x".format(byte.toInt() and 0xff)
            }
    }
}
