package com.omnio.tv.domain.repository

import com.omnio.tv.data.remote.dto.aiometadata.AioConfigInnerDto
import com.omnio.tv.domain.model.AgeRatingTier
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

    suspend fun getConfigPassword(): String?

    /**
     * Provisions a Kids-tuned AIOMetadata config for [targetProfileId] derived
     * from the Main profile's existing config. Copies API keys (TMDB, TVDB,
     * Fanart, MAL, etc.) and applies cert/genre filters scaled to
     * [maxAgeRating]. Saves to upstream, persists the bridge row, and adds the
     * resulting manifest URL to [targetProfileId]'s addon list.
     *
     * Returns the new [CreateConfigResult] on success. No-op (returns failure)
     * if Main has not configured AIOMetadata yet — caller can fall back to the
     * client-side filter in that case.
     */
    suspend fun provisionForKidsProfile(
        targetProfileId: Int,
        maxAgeRating: AgeRatingTier?,
    ): Result<CreateConfigResult>

    data class CreateConfigResult(val uuid: String, val manifestUrl: String)
}
