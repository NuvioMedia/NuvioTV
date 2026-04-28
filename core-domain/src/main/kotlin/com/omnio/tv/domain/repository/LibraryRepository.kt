package com.omnio.tv.domain.repository

import com.omnio.tv.domain.model.LibraryEntry
import com.omnio.tv.domain.model.LibraryEntryInput
import com.omnio.tv.domain.model.LibraryListTab
import com.omnio.tv.domain.model.LibrarySourceMode
import com.omnio.tv.domain.model.ListMembershipChanges
import com.omnio.tv.domain.model.ListMembershipSnapshot
import com.omnio.tv.domain.model.TraktListPrivacy
import kotlinx.coroutines.flow.Flow

interface LibraryRepository {
    val sourceMode: Flow<LibrarySourceMode>
    val isSyncing: Flow<Boolean>
    val libraryItems: Flow<List<LibraryEntry>>
    val listTabs: Flow<List<LibraryListTab>>

    fun isInLibrary(itemId: String, itemType: String): Flow<Boolean>
    fun isInWatchlist(itemId: String, itemType: String): Flow<Boolean>

    suspend fun toggleDefault(item: LibraryEntryInput)
    suspend fun getMembershipSnapshot(item: LibraryEntryInput): ListMembershipSnapshot
    suspend fun applyMembershipChanges(item: LibraryEntryInput, changes: ListMembershipChanges)

    suspend fun createPersonalList(
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    )

    suspend fun updatePersonalList(
        listId: String,
        name: String,
        description: String?,
        privacy: TraktListPrivacy
    )

    suspend fun deletePersonalList(listId: String)
    suspend fun reorderPersonalLists(orderedListIds: List<String>)
    suspend fun refreshNow()
}
