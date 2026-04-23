package com.omnio.tv.data.remote.api

import com.omnio.tv.data.remote.dto.aiometadata.AioConfigLoadRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigLoadResponseDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigSaveRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigSaveResponseDto
import com.omnio.tv.data.remote.dto.aiometadata.AioConfigUpdateRequestDto
import com.omnio.tv.data.remote.dto.aiometadata.AioTrustedResponseDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path

/**
 * REST client for the self-hosted cedya77/aiometadata instance.
 * Base URL is configured in NetworkModule from BuildConfig.AIOMETADATA_BASE_URL.
 */
interface AioMetadataApi {

    @POST("api/config/save")
    suspend fun saveConfig(
        @Body body: AioConfigSaveRequestDto,
    ): Response<AioConfigSaveResponseDto>

    @POST("api/config/load/{uuid}")
    suspend fun loadConfig(
        @Path("uuid") uuid: String,
        @Body body: AioConfigLoadRequestDto,
    ): Response<AioConfigLoadResponseDto>

    @PUT("api/config/update/{uuid}")
    suspend fun updateConfig(
        @Path("uuid") uuid: String,
        @Body body: AioConfigUpdateRequestDto,
    ): Response<AioConfigSaveResponseDto>

    @GET("api/config/is-trusted/{uuid}")
    suspend fun isTrusted(
        @Path("uuid") uuid: String,
    ): Response<AioTrustedResponseDto>
}
