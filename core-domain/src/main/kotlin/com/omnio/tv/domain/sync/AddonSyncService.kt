package com.omnio.tv.domain.sync

interface AddonSyncService {
    suspend fun pushToRemote(): Result<Unit>
    suspend fun getRemoteAddonUrls(): Result<List<String>>
}
