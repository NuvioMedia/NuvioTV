package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.Addon
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface AddonRepository {
    val refreshRevision: StateFlow<Long>
    fun getInstalledAddons(): Flow<List<Addon>>
    suspend fun fetchAddon(baseUrl: String, forceRefresh: Boolean = false): NetworkResult<Addon>
    suspend fun addAddon(url: String)
    suspend fun removeAddon(url: String)
    suspend fun setAddonOrder(urls: List<String>)
    suspend fun setAddonEnabled(url: String, enabled: Boolean)
}
