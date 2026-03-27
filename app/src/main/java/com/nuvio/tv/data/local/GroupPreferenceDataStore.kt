package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.domain.model.CatalogGroup
import com.nuvio.tv.domain.model.MainGroup
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class GroupPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "group_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    
    private val catalogGroupsKey = stringPreferencesKey("catalog_groups")
    private val mainGroupsKey = stringPreferencesKey("main_groups")

    private fun <T> profileFlow(extract: (prefs: androidx.datastore.preferences.core.Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            factory.get(pid, FEATURE).data.map { prefs -> extract(prefs) }
        }

    val catalogGroups: Flow<List<CatalogGroup>> = profileFlow { prefs ->
        parseCatalogGroups(prefs[catalogGroupsKey])
    }

    val mainGroups: Flow<List<MainGroup>> = profileFlow { prefs ->
        parseMainGroups(prefs[mainGroupsKey])
    }

    suspend fun setCatalogGroups(groups: List<CatalogGroup>) {
        store().edit { prefs ->
            if (groups.isEmpty()) {
                prefs.remove(catalogGroupsKey)
            } else {
                prefs[catalogGroupsKey] = gson.toJson(groups)
            }
        }
    }

    suspend fun setMainGroups(groups: List<MainGroup>) {
        store().edit { prefs ->
            if (groups.isEmpty()) {
                prefs.remove(mainGroupsKey)
            } else {
                prefs[mainGroupsKey] = gson.toJson(groups)
            }
        }
    }

    private fun parseCatalogGroups(json: String?): List<CatalogGroup> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<CatalogGroup>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun parseMainGroups(json: String?): List<MainGroup> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<MainGroup>>() {}.type
            gson.fromJson(json, type) ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    suspend fun updateCatalogGroup(group: CatalogGroup) {
        store().edit { prefs ->
            val json = prefs[catalogGroupsKey]
            val currentGroups = parseCatalogGroups(json).toMutableList()
            val index = currentGroups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                currentGroups[index] = group
            } else {
                currentGroups.add(group)
            }
            prefs[catalogGroupsKey] = gson.toJson(currentGroups)
        }
    }

    suspend fun updateMainGroup(group: MainGroup) {
        store().edit { prefs ->
            val json = prefs[mainGroupsKey]
            val currentGroups = parseMainGroups(json).toMutableList()
            val index = currentGroups.indexOfFirst { it.id == group.id }
            if (index != -1) {
                currentGroups[index] = group
            } else {
                currentGroups.add(group)
            }
            prefs[mainGroupsKey] = gson.toJson(currentGroups)
        }
    }

    suspend fun removeCatalogGroup(id: String) {
        store().edit { prefs ->
            val cgJson = prefs[catalogGroupsKey]
            val currentGroups = parseCatalogGroups(cgJson)
            val updatedCg = currentGroups.filter { it.id != id }
            if (updatedCg.isEmpty()) {
                prefs.remove(catalogGroupsKey)
            } else {
                prefs[catalogGroupsKey] = gson.toJson(updatedCg)
            }

            val mgJson = prefs[mainGroupsKey]
            val currentMainGroups = parseMainGroups(mgJson)
            val updatedMain = currentMainGroups.map { main ->
                main.copy(subGroupIds = main.subGroupIds.filter { it != id })
            }
            if (updatedMain.isEmpty()) {
                prefs.remove(mainGroupsKey)
            } else {
                prefs[mainGroupsKey] = gson.toJson(updatedMain)
            }
        }
    }

    suspend fun removeMainGroup(id: String) {
        store().edit { prefs ->
            val mgJson = prefs[mainGroupsKey]
            val currentGroups = parseMainGroups(mgJson)
            val updatedMg = currentGroups.filter { it.id != id }
            if (updatedMg.isEmpty()) {
                prefs.remove(mainGroupsKey)
            } else {
                prefs[mainGroupsKey] = gson.toJson(updatedMg)
            }
        }
    }
}
