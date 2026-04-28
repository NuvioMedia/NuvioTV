package com.nuvio.tv.data.local

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private class ScopedDataStore(
    val store: DataStore<Preferences>,
    val scope: CoroutineScope,
    val job: Job
)

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, ScopedDataStore>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()
    private val lock = Any()

    /** Set of DataStore file names that were reset due to corruption during this session. */
    val corruptedFileNames: MutableSet<String> = ConcurrentHashMap.newKeySet()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
        synchronized(lock) {
            cache[fileName]?.let { return it.store }
            return createAndCache(fileName).store
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p${profileId}"
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

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }

    private fun createAndCache(fileName: String): ScopedDataStore {
        val job = SupervisorJob()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = androidx.datastore.core.handlers.ReplaceFileCorruptionHandler { ex ->
                Log.e("ProfileDataStoreFactory", "DataStore corrupted ($fileName): ${ex.message} — resetting to empty preferences")
                corruptedFileNames.add(fileName)
                emptyPreferences()
            },
            scope = scope,
            produceFile = { context.preferencesDataStoreFile(fileName) }
        )
        val scoped = ScopedDataStore(store, scope, job)
        cache[fileName] = scoped
        return scoped
    }
}
