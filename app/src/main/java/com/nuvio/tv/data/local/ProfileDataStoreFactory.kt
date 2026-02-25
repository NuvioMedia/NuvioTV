package com.nuvio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
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

    fun get(profileId: Int, featureName: String): DataStore<Preferences> {
        val fileName = if (profileId == 1) featureName else "${featureName}_p${profileId}"
        return cache.getOrPut(fileName) {
            PreferenceDataStoreFactory.create {
                context.preferencesDataStoreFile(fileName)
            }
        }
    }

    fun clearProfile(profileId: Int) {
        if (profileId == 1) return
        val suffix = "_p${profileId}"
        val keysToRemove = cache.keys.filter { key -> key.endsWith(suffix) }
        keysToRemove.forEach { key -> cache.remove(key) }
    }
}
