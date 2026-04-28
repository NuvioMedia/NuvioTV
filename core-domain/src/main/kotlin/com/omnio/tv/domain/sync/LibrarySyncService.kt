package com.omnio.tv.domain.sync

import com.omnio.tv.domain.model.SavedLibraryItem

interface LibrarySyncService {
    suspend fun pushToRemote(): Result<Unit>
    suspend fun pullFromRemote(): Result<List<SavedLibraryItem>>
}
