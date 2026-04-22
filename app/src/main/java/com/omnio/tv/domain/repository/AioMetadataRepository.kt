package com.omnio.tv.domain.repository

import com.omnio.tv.data.remote.dto.aiometadata.AioConfigRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigResponseDto
import com.omnio.tv.domain.model.AioMetadataSettings
import kotlinx.coroutines.flow.Flow

interface AioMetadataRepository {
    val settings: Flow<AioMetadataSettings>

    fun cachedConfig(): AioConfigResponseDto?

    suspend fun refresh(): Result<AioConfigResponseDto?>

    suspend fun createConfig(request: AioConfigRequestDto): Result<String>

    suspend fun updateConfig(
        uuid: String,
        request: AioConfigRequestDto
    ): Result<AioConfigResponseDto>

    suspend fun setEnabled(enabled: Boolean, manifestUrl: String): Result<Unit>
}
