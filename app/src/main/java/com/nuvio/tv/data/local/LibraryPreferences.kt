package com.nuvio.tv.data.local

import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import com.google.gson.Gson
import com.nuvio.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "library_preferences"
        private const val TAG = "LibraryPrefs"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")

    val libraryItems: Flow<List<SavedLibraryItem>> = profileManager.activeProfileId.flatMapLatest { pid ->
        factory.get(pid, FEATURE).data.map { preferences ->
            val raw = preferences[libraryItemsKey] ?: emptySet()
            raw.mapNotNull { json ->
                runCatching { gson.fromJson(json, SavedLibraryItem::class.java) }.getOrNull()
            }
        }
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryItems.map { items ->
            items.any { it.id == itemId && it.type.equals(itemType, ignoreCase = true) }
        }
    }

    suspend fun addItem(item: SavedLibraryItem) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    saved.id == item.id && saved.type.equals(item.type, ignoreCase = true)
                } ?: false
            }
            preferences[libraryItemsKey] = filtered.toSet() + gson.toJson(item)
        }
    }

    suspend fun removeItem(itemId: String, itemType: String) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            val filtered = current.filterNot { json ->
                runCatching {
                    gson.fromJson(json, SavedLibraryItem::class.java)
                }.getOrNull()?.let { saved ->
                    saved.id == itemId && saved.type.equals(itemType, ignoreCase = true)
                } ?: false
            }
            preferences[libraryItemsKey] = filtered.toSet()
        }
    }

    suspend fun getAllItems(): List<SavedLibraryItem> {
        return libraryItems.first()
    }

    suspend fun mergeRemoteItems(remoteItems: List<SavedLibraryItem>) {
        store().edit { preferences ->
            val current = preferences[libraryItemsKey] ?: emptySet()
            if (remoteItems.isEmpty() && current.isNotEmpty()) {
                Log.w(TAG, "mergeRemoteItems: remote list empty while local has ${current.size} entries; preserving local library")
                return@edit
            }
            val dedupedRemote = linkedMapOf<Pair<String, String>, SavedLibraryItem>()
            remoteItems.forEach { item ->
                dedupedRemote[item.id to item.type.lowercase()] = item
            }
            preferences[libraryItemsKey] = dedupedRemote.values
                .map { gson.toJson(it) }
                .toSet()
        }
    }
}
