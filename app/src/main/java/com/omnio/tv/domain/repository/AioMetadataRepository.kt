package com.omnio.tv.domain.repository

import com.omnio.tv.data.remote.dto.aiometadata.AioConfigInnerDto
import com.omnio.tv.domain.model.AioMetadataSettings
import kotlinx.coroutines.flow.Flow

interface AioMetadataRepository {
    val settings: Flow<AioMetadataSettings>

    fun cachedConfig(): AioConfigInnerDto?

    suspend fun refresh(): Result<AioConfigInnerDto?>

    suspend fun createConfig(config: AioConfigInnerDto): Result<CreateConfigResult>

    suspend fun updateConfig(
        uuid: String,
        config: AioConfigInnerDto,
    ): Result<AioConfigInnerDto>

    suspend fun setEnabled(enabled: Boolean, manifestUrl: String): Result<Unit>

    data class CreateConfigResult(val uuid: String, val manifestUrl: String)
}
