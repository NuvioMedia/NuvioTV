package com.omnio.tv.domain.repository

import com.omnio.tv.core.network.NetworkResult
import com.omnio.tv.domain.model.Meta
import kotlinx.coroutines.flow.Flow

interface MetaRepository {
    fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun getMetaFromAllAddons(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>

    fun getMetaFromPrimaryAddon(
        type: String,
        id: String
    ): Flow<NetworkResult<Meta>>
    
    fun clearCache()
}
