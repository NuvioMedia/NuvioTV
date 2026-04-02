package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.emby.*
import retrofit2.Response
import retrofit2.http.*

interface EmbyApi {
    @GET("System/Info")
    suspend fun getSystemInfo(): Response<EmbySystemInfoDto>

    @GET("Users")
    suspend fun getUsers(): Response<List<EmbyUserDto>>

    @GET("Users/{userId}/Items")
    suspend fun getUserItems(
        @Path("userId") userId: String,
        @Query("IncludeItemTypes") includeItemTypes: String,
        @Query("AnyProviderIdEquals") providerIds: String,
        @Query("Fields") fields: String = "ProviderIds,RunTimeTicks",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Limit") limit: Int = 1
    ): Response<EmbyItemsResponseDto>

    @GET("Shows/{seriesId}/Episodes")
    suspend fun getEpisodes(
        @Path("seriesId") seriesId: String,
        @Query("SeasonNumber") season: Int,
        @Query("EpisodeNumber") episode: Int,
        @Query("Fields") fields: String = "RunTimeTicks",
        @Query("Limit") limit: Int = 1
    ): Response<EmbyItemsResponseDto>

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(@Body body: EmbyPlaybackStartDto): Response<Unit>

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: EmbyPlaybackProgressDto): Response<Unit>

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: EmbyPlaybackStopDto): Response<Unit>
}
