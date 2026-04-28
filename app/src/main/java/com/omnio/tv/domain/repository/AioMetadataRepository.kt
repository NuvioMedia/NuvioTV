package com.omnio.tv.domain.repository

import com.omnio.tv.domain.model.AioConfigInnerDto
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
     * Provisions a fresh AIOMetadata config for [targetProfileId] derived from
     * the Main profile's existing config. Copies API keys (TMDB, TVDB, Fanart,
     * MAL, etc.); applies Kids-tuned catalog filters when [kidsMaxAgeRating]
     * is non-null. Saves to upstream, persists the bridge row, and adds the
     * resulting manifest URL to [targetProfileId]'s addon list.
     *
     * The [sharingMode] is recorded by the caller on the profile itself; this
     * function only handles the initial provisioning step. Future Main updates
     * are propagated automatically through [updateConfig]'s fan-out based on
     * each profile's stored sharing mode.
     */
    suspend fun provisionFromMain(
        targetProfileId: Int,
        kidsMaxAgeRating: AgeRatingTier? = null,
    ): Result<CreateConfigResult>

    data class CreateConfigResult(val uuid: String, val manifestUrl: String)
}
