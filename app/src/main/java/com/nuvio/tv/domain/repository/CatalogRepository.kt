package com.nuvio.tv.domain.repository

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogRow
import kotlinx.coroutines.flow.Flow

interface CatalogRepository {
    fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int = 0,
        skipStep: Int = 100,
        extraArgs: Map<String, String> = emptyMap(),
        supportsSkip: Boolean = false,
        posterScreen: com.nuvio.tv.core.poster.CustomPosterScreen = com.nuvio.tv.core.poster.CustomPosterScreen.HOME
    ): Flow<NetworkResult<CatalogRow>>
}
