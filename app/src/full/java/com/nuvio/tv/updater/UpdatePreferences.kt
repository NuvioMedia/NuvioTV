package com.nuvio.tv.updater

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nuvio.tv.BuildConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.updateDataStore: DataStore<Preferences> by preferencesDataStore(name = "update_settings")

@Singleton
class UpdatePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.updateDataStore

    private val ignoredTagKey = stringPreferencesKey("ignored_release_tag")
    private val lastCheckAtKey = longPreferencesKey("last_check_at_ms")
    private val updateBannerEnabledKey = booleanPreferencesKey("update_banner_enabled")
    private val updateChannelKey = stringPreferencesKey("update_channel")
    private val autoInstallEnabledKey = booleanPreferencesKey("auto_install_enabled")
    private val downloadedTagKey = stringPreferencesKey("downloaded_release_tag")
    private val downloadedApkPathKey = stringPreferencesKey("downloaded_apk_path")
    private val autoInstalledTagKey = stringPreferencesKey("auto_installed_release_tag")
    private val autoInstallNoticeEnabledKey = booleanPreferencesKey("auto_install_notice_enabled")

    val ignoredTag: Flow<String?> = dataStore.data.map { prefs ->
        prefs[ignoredTagKey]
    }

    val lastCheckAtMs: Flow<Long> = dataStore.data.map { prefs ->
        prefs[lastCheckAtKey] ?: 0L
    }

    val updateBannerEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[updateBannerEnabledKey] ?: true
    }

    val updateChannel: Flow<UpdateChannel> = dataStore.data.map { prefs ->
        UpdateChannel.fromStoredValue(prefs[updateChannelKey])
            ?: UpdateChannel.defaultForVersion(BuildConfig.VERSION_NAME)
    }

    val autoInstallEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[autoInstallEnabledKey] ?: false
    }

    /** Release tag and APK path of the update already downloaded, if any, so it survives the app being closed. */
    val downloadedUpdate: Flow<Pair<String, String>?> = dataStore.data.map { prefs ->
        val tag = prefs[downloadedTagKey]
        val path = prefs[downloadedApkPathKey]
        if (tag != null && path != null) tag to path else null
    }

    /** Release the idle job last installed without asking, so the next launch can say the app was updated. */
    val autoInstalledTag: Flow<String?> = dataStore.data.map { prefs ->
        prefs[autoInstalledTagKey]
    }

    val autoInstallNoticeEnabled: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[autoInstallNoticeEnabledKey] ?: true
    }

    suspend fun getOrInitializeUpdateChannel(): UpdateChannel {
        val defaultChannel = UpdateChannel.defaultForVersion(BuildConfig.VERSION_NAME)
        var resolvedChannel = defaultChannel
        dataStore.edit { prefs ->
            resolvedChannel = UpdateChannel.fromStoredValue(prefs[updateChannelKey])
                ?: defaultChannel
            prefs[updateChannelKey] = resolvedChannel.storedValue
        }
        return resolvedChannel
    }

    suspend fun setIgnoredTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(ignoredTagKey) else prefs[ignoredTagKey] = tag
        }
    }

    suspend fun setLastCheckAtMs(value: Long) {
        dataStore.edit { prefs ->
            prefs[lastCheckAtKey] = value
        }
    }

    suspend fun setUpdateBannerEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[updateBannerEnabledKey] = enabled
        }
    }

    suspend fun setAutoInstallEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoInstallEnabledKey] = enabled
        }
    }

    suspend fun setDownloadedUpdate(tag: String?, apkPath: String?) {
        dataStore.edit { prefs ->
            if (tag == null || apkPath == null) {
                prefs.remove(downloadedTagKey)
                prefs.remove(downloadedApkPathKey)
            } else {
                prefs[downloadedTagKey] = tag
                prefs[downloadedApkPathKey] = apkPath
            }
        }
    }

    suspend fun setAutoInstallNoticeEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[autoInstallNoticeEnabledKey] = enabled
        }
    }

    suspend fun setAutoInstalledTag(tag: String?) {
        dataStore.edit { prefs ->
            if (tag == null) prefs.remove(autoInstalledTagKey) else prefs[autoInstalledTagKey] = tag
        }
    }

    suspend fun setUpdateChannel(channel: UpdateChannel) {
        dataStore.edit { prefs ->
            prefs[updateChannelKey] = channel.storedValue
            prefs.remove(ignoredTagKey)
        }
    }
}
