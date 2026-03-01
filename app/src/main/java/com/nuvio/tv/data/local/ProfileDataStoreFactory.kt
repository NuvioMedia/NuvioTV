package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileDataStoreFactory @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val cache = ConcurrentHashMap<String, DataStore<Preferences>>()
    private val deletedProfileIds = ConcurrentHashMap.newKeySet<Int>()

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
        if (profileId != 1 && profileId in deletedProfileIds) {
            return cache.compute(fileName) { _, _ ->
                PreferenceDataStoreFactory.create {
                    context.preferencesDataStoreFile(fileName)
                }
            }!!
        }
        return cache.getOrPut(fileName) {
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(fileName)
            }
        }
    }

    suspend fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        deletedProfileIds.add(profileId)
        val suffix = "_p${profileId}"
        val keysToRemove = cache.keys.filter { key -> key.endsWith(suffix) }
        for (key in keysToRemove) {
            val store = cache[key]
            if (store != null) {
                runCatching { store.edit { it.clear() } }
            }
            cache.remove(key)
        }
    }

    fun isProfileDeleted(profileId: Int): Boolean = profileId in deletedProfileIds

    fun markProfileCreated(profileId: Int) {
        deletedProfileIds.remove(profileId)
    }
}
