package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.nuvio.tv.domain.model.HiddenItemEntry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HiddenItemsPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val hiddenItemsKey = stringSetPreferencesKey("hidden_items")

    val hiddenItems: Flow<List<HiddenItemEntry>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val raw = preferences[hiddenItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                runCatching { gson.fromJson(json, HiddenItemEntry::class.java) }.getOrNull()
            }.sortedByDescending { it.hiddenAt }
        }
    }

    val hiddenKeys: Flow<Set<String>> = hiddenItems.map { items ->
        items.map { hiddenKey(it.itemId, it.itemType) }.toSet()
    }

    fun isHidden(itemId: String, itemType: String): Flow<Boolean> {
        return hiddenKeys.map { keys ->
            hiddenKey(itemId, itemType) in keys
        }
    }

    suspend fun hideItem(entry: HiddenItemEntry) {
        store().edit { preferences ->
            val current = preferences[hiddenItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, HiddenItemEntry::class.java)
                }.getOrNull()?.let { saved ->
                    saved.itemId == entry.itemId &&
                        saved.itemType.equals(entry.itemType, ignoreCase = true)
                } ?: false
            }
            val entryWithTimestamp = if (entry.hiddenAt == 0L) {
                entry.copy(hiddenAt = System.currentTimeMillis())
            } else {
                entry
            }
            preferences[hiddenItemsKey] = filtered.toSet() + gson.toJson(entryWithTimestamp)
        }
    }

    suspend fun unhideItem(itemId: String, itemType: String) {
        store().edit { preferences ->
            val current = preferences[hiddenItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, HiddenItemEntry::class.java)
                }.getOrNull()?.let { saved ->
                    saved.itemId == itemId &&
                        saved.itemType.equals(itemType, ignoreCase = true)
                } ?: false
            }
            preferences[hiddenItemsKey] = filtered.toSet()
        }
    }

    companion object {
        private const val FEATURE = "hidden_items"

        fun hiddenKey(itemId: String, itemType: String): String {
            return "${itemType.lowercase()}|$itemId"
        }
    }
}
