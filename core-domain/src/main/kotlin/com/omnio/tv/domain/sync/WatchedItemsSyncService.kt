package com.omnio.tv.domain.sync

import com.omnio.tv.domain.model.WatchedItem

interface WatchedItemsSyncService {
    suspend fun pushToRemote(): Result<Unit>
    suspend fun pullFromRemote(): Result<List<WatchedItem>>
    suspend fun deleteFromRemote(
        contentId: String,
        season: Int?,
        episode: Int?
    ): Result<Unit>
    suspend fun deleteFromRemoteBatch(
        contentId: String,
        episodes: List<Pair<Int, Int>>
    ): Result<Unit>
}
