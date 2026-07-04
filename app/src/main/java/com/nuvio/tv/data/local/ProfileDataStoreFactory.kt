package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataMigration
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.nuvio.tv.domain.model.DiscoverLocation
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import java.io.File

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

internal val discoverLocationMigration = object : DataMigration<Preferences> {
    override suspend fun shouldMigrate(currentData: Preferences): Boolean =
        legacySearchDiscoverEnabledKey in currentData

    override suspend fun migrate(currentData: Preferences): Preferences {
        val mutable = currentData.toMutablePreferences()
        val legacy = mutable[legacySearchDiscoverEnabledKey]
        if (legacy != null && mutable[discoverLocationKey] == null) {
            val rememberedLocation = mutable[lastNonOffDiscoverLocationKey]?.let {
                runCatching { DiscoverLocation.valueOf(it) }.getOrNull()
            }?.takeIf { it != DiscoverLocation.OFF }
            val resolved = if (legacy && rememberedLocation != null) {
                rememberedLocation
            } else {
                DiscoverLocation.fromLegacySearchDiscoverEnabled(legacy)
            }
            mutable[discoverLocationKey] = resolved.name
        }
        mutable.remove(legacySearchDiscoverEnabledKey)
        return mutable.toPreferences()
    }

    override suspend fun cleanUp() = Unit
}

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()
    private val retainedStandaloneDataStoreNames = setOf(
        "app_onboarding",
        "auth_session_notice_store",
        "debug_settings",
        "device_local_player_prefs",
        "profile_lock_state",
        "profile_settings",
        "sentry_settings",
        "torrent_settings",
        "tv_channel_prefs"
    )

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p$profileId"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p$profileId"
        val keysToRemove = synchronized(lock) {
            cache.keys.filter { it.endsWith(suffix) }
        }
        for (key in keysToRemove) {
            val scoped = synchronized(lock) { cache.remove(key) } ?: continue
            runCatching { scoped.store.edit { it.clear() } }
            // Cancel the scope so DataStore releases the file from its active-files
            // registry. Wait for completion before any subsequent get() can create
            // a fresh DataStore for the same path.
            scoped.job.cancel()
            scoped.job.join()
        }
    }

    suspend fun clearProfileScopedData() = withContext(Dispatchers.IO) {
        val cachedStores = synchronized(lock) { cache.toMap() }
        cachedStores.values.forEach { scoped ->
            runCatching { scoped.store.edit { it.clear() } }
        }
        deletedProfileIds.clear()
        corruptedFileNames.clear()

        val cachedFileNames = cachedStores.keys.mapTo(mutableSetOf()) { "$it.preferences_pb" }
        val dataStoreDir = File(context.filesDir, "datastore")
        if (!dataStoreDir.exists()) return@withContext
        dataStoreDir.listFiles()?.forEach { file ->
            if (file.name !in cachedFileNames && isProfileScopedDataStoreFile(file.name)) {
                file.delete()
            }
        }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }

    private fun isProfileScopedDataStoreFile(fileName: String): Boolean {
        if (!fileName.endsWith(".preferences_pb")) return false
        val dataStoreName = fileName.removeSuffix(".preferences_pb")
        return dataStoreName !in retainedStandaloneDataStoreNames
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val migrations = if (fileName == "layout_settings" || fileName.startsWith("layout_settings_p")) {
            listOf(discoverLocationMigration)
        } else {
            emptyList()
        }
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — resetting to empty preferences")
                corruptedFileNames.add(fileName)
                emptyPreferences()
            },
            scope = scope,
            migrations = migrations,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )
        val scoped = ScopedDataStore(store, scope, job)
        cache[fileName] = scoped
        return scoped
    }
}
