package com.omnio.tv.domain.sync

import com.omnio.tv.domain.model.WatchProgress

interface WatchProgressSyncService {
    suspend fun shouldUseSupabaseWatchProgressSync(): Boolean
    suspend fun deleteFromRemote(keys: Collection<String>): Result<Unit>
    suspend fun pushToRemote(): Result<Unit>
    suspend fun pushSingleToRemote(key: String, progress: WatchProgress): Result<Unit>
    suspend fun pullFromRemote(): Result<List<Pair<String, WatchProgress>>>
}
