package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.google.gson.Gson
import com.nuvio.tv.core.profile.ProfileManager
import com.nuvio.tv.core.sync.library.LibrarySyncLocalStore
import com.nuvio.tv.domain.model.LibraryDeltaApplyResult
import com.nuvio.tv.domain.model.LibraryDeltaEvent
import com.nuvio.tv.domain.model.LibrarySnapshotApplyResult
import com.nuvio.tv.domain.model.LibrarySyncKey
import com.nuvio.tv.domain.model.LibrarySyncReducer
import com.nuvio.tv.domain.model.LibrarySyncState
import com.nuvio.tv.domain.model.SavedLibraryItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryPreferences @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) : LibrarySyncLocalStore {
    private val gson = Gson()
    private val libraryItemsKey = stringSetPreferencesKey("library_items")
    private val sortOptionKey = stringPreferencesKey("library_sort_option")
    private val lastSelectedListKey = stringPreferencesKey("library_last_selected_list")
    private val lastSelectedTypeKey = stringPreferencesKey("library_last_selected_type")
    private val deltaCursorKey = longPreferencesKey("library_delta_cursor")
    private val deltaInitializedKey = booleanPreferencesKey("library_delta_initialized")
    private val pendingUpsertKeysKey = stringSetPreferencesKey("library_pending_upserts")
    private val pendingDeleteKeysKey = stringSetPreferencesKey("library_pending_deletes")
    private val mutationRevisionKey = longPreferencesKey("library_mutation_revision")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val sortOption: Flow<String?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[sortOptionKey]
        }
    }

    val libraryItems: Flow<List<SavedLibraryItem>> =
        profileManager.activeProfileId.flatMapLatest { profileId ->
            factory.get(profileId, FEATURE).data.map { preferences ->
                preferences.toLibrarySyncState().items
            }
        }

    suspend fun setSortOption(key: String) {
        store().edit { preferences ->
            preferences[sortOptionKey] = key
        }
    }

    val lastSelectedList: Flow<String?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[lastSelectedListKey]
        }
    }

    suspend fun setLastSelectedList(key: String) {
        store().edit { preferences ->
            preferences[lastSelectedListKey] = key
        }
    }

    val lastSelectedType: Flow<String?> = profileManager.activeProfileId.flatMapLatest { profileId ->
        factory.get(profileId, FEATURE).data.map { preferences ->
            preferences[lastSelectedTypeKey]
        }
    }

    suspend fun setLastSelectedType(key: String) {
        store().edit { preferences ->
            preferences[lastSelectedTypeKey] = key
        }
    }

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean> {
        return libraryItems.map { items ->
            items.any { item ->
                item.id == itemId && item.type.equals(itemType, ignoreCase = true)
            }
        }
    }

    suspend fun containsItem(
        itemId: String,
        itemType: String,
        profileId: Int = profileManager.activeProfileId.value
    ): Boolean {
        return getSyncState(profileId).items.any { item ->
            item.id == itemId && item.type.equals(itemType, ignoreCase = true)
        }
    }

    suspend fun addItem(
        item: SavedLibraryItem,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val updated = LibrarySyncReducer.upsertLocal(
                state = preferences.toLibrarySyncState(),
                item = item,
                nowEpochMs = System.currentTimeMillis()
            )
            preferences.writeLibrarySyncState(updated)
        }
    }

    suspend fun removeItem(
        itemId: String,
        itemType: String,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val updated = LibrarySyncReducer.deleteLocal(
                state = preferences.toLibrarySyncState(),
                contentId = itemId,
                contentType = itemType
            )
            preferences.writeLibrarySyncState(updated)
        }
    }

    suspend fun getAllItems(
        profileId: Int = profileManager.activeProfileId.value
    ): List<SavedLibraryItem> {
        return getSyncState(profileId).items
    }

    suspend fun updateLogo(
        id: String,
        type: String,
        logo: String,
        profileId: Int = profileManager.activeProfileId.value
    ) {
        store(profileId).edit { preferences ->
            val state = preferences.toLibrarySyncState()
            val updatedItems = state.items.map { item ->
                if (item.id == id && item.type.equals(type, ignoreCase = true)) {
                    item.copy(logo = logo)
                } else {
                    item
                }
            }
            preferences.writeLibrarySyncState(state.copy(items = updatedItems))
        }
    }

    override suspend fun getSyncState(profileId: Int): LibrarySyncState {
        return store(profileId).data.first().toLibrarySyncState()
    }

    override suspend fun applyRemoteSnapshot(
        profileId: Int,
        remoteItems: Collection<SavedLibraryItem>,
        cursorEventId: Long
    ): LibrarySnapshotApplyResult {
        lateinit var result: LibrarySnapshotApplyResult
        store(profileId).edit { preferences ->
            result = LibrarySyncReducer.applySnapshot(
                state = preferences.toLibrarySyncState(),
                remoteItems = remoteItems,
                cursorEventId = cursorEventId
            )
            preferences.writeLibrarySyncState(result.state)
        }
        return result
    }

    override suspend fun applyRemoteDelta(
        profileId: Int,
        events: Collection<LibraryDeltaEvent>
    ): LibraryDeltaApplyResult {
        lateinit var result: LibraryDeltaApplyResult
        store(profileId).edit { preferences ->
            result = LibrarySyncReducer.applyDelta(
                state = preferences.toLibrarySyncState(),
                events = events
            )
            preferences.writeLibrarySyncState(result.state)
        }
        return result
    }

    override suspend fun queueAllItemsForPush(profileId: Int): LibrarySyncState {
        lateinit var result: LibrarySyncState
        store(profileId).edit { preferences ->
            result = LibrarySyncReducer.queueAllItemsForPush(
                preferences.toLibrarySyncState()
            )
            preferences.writeLibrarySyncState(result)
        }
        return result
    }

    override suspend fun acknowledgePush(
        profileId: Int,
        expectedMutationRevision: Long
    ): Boolean {
        var acknowledged = false
        store(profileId).edit { preferences ->
            val updated = LibrarySyncReducer.acknowledgePush(
                state = preferences.toLibrarySyncState(),
                expectedMutationRevision = expectedMutationRevision
            )
            if (updated != null) {
                preferences.writeLibrarySyncState(updated)
                acknowledged = true
            }
        }
        return acknowledged
    }

    private fun Preferences.toLibrarySyncState(): LibrarySyncState {
        return LibrarySyncReducer.sanitize(
            LibrarySyncState(
                items = decodeSet(libraryItemsKey, SavedLibraryItem::class.java),
                deltaCursorEventId = this[deltaCursorKey] ?: 0L,
                deltaInitialized = this[deltaInitializedKey] ?: false,
                pendingUpsertKeys = decodeSet(pendingUpsertKeysKey, LibrarySyncKey::class.java),
                pendingDeleteKeys = decodeSet(pendingDeleteKeysKey, LibrarySyncKey::class.java),
                mutationRevision = this[mutationRevisionKey] ?: 0L
            )
        )
    }

    private fun MutablePreferences.writeLibrarySyncState(state: LibrarySyncState) {
        val sanitized = LibrarySyncReducer.sanitize(state)
        this[libraryItemsKey] = sanitized.items.map(gson::toJson).toSet()
        this[deltaCursorKey] = sanitized.deltaCursorEventId
        this[deltaInitializedKey] = sanitized.deltaInitialized
        this[pendingUpsertKeysKey] = sanitized.pendingUpsertKeys.map(gson::toJson).toSet()
        this[pendingDeleteKeysKey] = sanitized.pendingDeleteKeys.map(gson::toJson).toSet()
        this[mutationRevisionKey] = sanitized.mutationRevision
    }

    private fun <T> Preferences.decodeSet(
        key: Preferences.Key<Set<String>>,
        type: Class<T>
    ): List<T> {
        return this[key].orEmpty().mapNotNull { value ->
            runCatching { gson.fromJson(value, type) }.getOrNull()
        }
    }

    private companion object {
        const val FEATURE = "library_preferences"
    }
}
