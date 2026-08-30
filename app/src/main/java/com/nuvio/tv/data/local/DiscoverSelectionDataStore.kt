package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DiscoverSelectionDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private companion object {
        const val FEATURE = "discover_selection"
        val selectedCatalogKey = stringPreferencesKey("selected_catalog_key")
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    suspend fun getSelectedCatalogKey(): String? =
        store().data.first()[selectedCatalogKey]
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    suspend fun setSelectedCatalogKey(catalogKey: String) {
        val normalized = catalogKey.trim()
        if (normalized.isEmpty()) return
        store().edit { preferences ->
            preferences[selectedCatalogKey] = normalized
        }
    }
}
