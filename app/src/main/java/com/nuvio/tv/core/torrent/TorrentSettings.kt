package com.nuvio.tv.core.torrent

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

private val Context.torrentDataStore by preferencesDataStore(
    name = "torrent_settings",
    corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { emptyPreferences() }
)

internal fun torrentSettingsDataStore(context: Context): DataStore<Preferences> = context.torrentDataStore

data class TorrentSettingsData(
    val p2pEnabled: Boolean = false,
    val enableUpload: Boolean = true,
    val hideTorrentStats: Boolean = true,
    val customTorrServerUrl: String = ""
)

@Singleton
class TorrentSettings @Inject constructor(
    private val dataStore: DataStore<Preferences>
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private object Keys {
        val P2P_ENABLED = booleanPreferencesKey("p2p_enabled")
        val ENABLE_UPLOAD = booleanPreferencesKey("enable_upload")
        val HIDE_TORRENT_STATS = booleanPreferencesKey("hide_torrent_stats")
        val CUSTOM_TORR_SERVER_URL = stringPreferencesKey("custom_torr_server_url")
    }

    @Volatile
    private var cachedCustomTorrServerUrl: String = ""

    init {
        scope.launch {
            dataStore.data.collect { prefs ->
                cachedCustomTorrServerUrl = prefs[Keys.CUSTOM_TORR_SERVER_URL] ?: ""
            }
        }
    }

    val settings: Flow<TorrentSettingsData> = dataStore.data.map { prefs ->
        TorrentSettingsData(
            p2pEnabled = prefs[Keys.P2P_ENABLED] ?: false,
            enableUpload = prefs[Keys.ENABLE_UPLOAD] ?: true,
            hideTorrentStats = prefs[Keys.HIDE_TORRENT_STATS] ?: true,
            customTorrServerUrl = prefs[Keys.CUSTOM_TORR_SERVER_URL] ?: ""
        ).also { cachedCustomTorrServerUrl = it.customTorrServerUrl }
    }

    val currentCustomTorrServerUrl: String
        get() = cachedCustomTorrServerUrl

    fun setP2pEnabled(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[Keys.P2P_ENABLED] = enabled }
        }
    }

    fun setEnableUpload(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[Keys.ENABLE_UPLOAD] = enabled }
        }
    }

    fun setHideTorrentStats(enabled: Boolean) {
        scope.launch {
            dataStore.edit { it[Keys.HIDE_TORRENT_STATS] = enabled }
        }
    }

    fun setCustomTorrServerUrl(url: String) {
        val normalized = url.trim()
        cachedCustomTorrServerUrl = normalized
        scope.launch {
            if (normalized.isBlank()) {
                dataStore.edit { it.remove(Keys.CUSTOM_TORR_SERVER_URL) }
            } else {
                dataStore.edit { it[Keys.CUSTOM_TORR_SERVER_URL] = normalized }
            }
        }
    }
}