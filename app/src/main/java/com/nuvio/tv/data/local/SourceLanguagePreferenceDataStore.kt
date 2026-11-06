package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceLanguagePreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private fun store() = factory.get(profileManager.activeProfileId.value, "source_language")
    private fun key(contentId: String) = stringPreferencesKey("language|$contentId")

    suspend fun get(contentId: String): String = store().data.first()[key(contentId)].orEmpty()

    suspend fun set(contentId: String, language: String) {
        if (contentId.isBlank()) return
        store().edit { preferences ->
            val preferenceKey = key(contentId)
            if (language.isBlank()) preferences.remove(preferenceKey) else preferences[preferenceKey] = language
        }
    }
}
