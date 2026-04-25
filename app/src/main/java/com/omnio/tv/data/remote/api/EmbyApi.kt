package com.omnio.tv.data.remote.api

import com.omnio.tv.data.remote.dto.emby.EmbyAuthByNameRequestDto
import com.omnio.tv.data.remote.dto.emby.EmbyAuthResponseDto
import com.omnio.tv.data.remote.dto.emby.EmbyItemsResponseDto
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackProgressDto
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackStartDto
import com.omnio.tv.data.remote.dto.emby.EmbyPlaybackStopDto
import com.omnio.tv.data.remote.dto.emby.EmbySystemInfoDto
import com.omnio.tv.data.remote.dto.emby.EmbyUserDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

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
        @Query("Fields") fields: String = "ProviderIds,RunTimeTicks,MediaSources,ParentIndexNumber,IndexNumber",
        @Query("Recursive") recursive: Boolean = true,
        @Query("Limit") limit: Int = 1
    ): Response<EmbyItemsResponseDto>

    @GET("Shows/{seriesId}/Episodes")
    suspend fun getEpisodes(
        @Path("seriesId") seriesId: String,
        @Query("Season") season: Int,
        @Query("IncludeItemTypes") includeItemTypes: String = "Episode",
        @Query("Fields") fields: String = "RunTimeTicks,MediaSources,ParentIndexNumber,IndexNumber"
    ): Response<EmbyItemsResponseDto>

    @POST("Users/AuthenticateByName")
    suspend fun authenticateByName(@Body body: EmbyAuthByNameRequestDto): Response<EmbyAuthResponseDto>

    @POST("Sessions/Playing")
    suspend fun reportPlaybackStart(@Body body: EmbyPlaybackStartDto): Response<Unit>

    @POST("Sessions/Playing/Progress")
    suspend fun reportPlaybackProgress(@Body body: EmbyPlaybackProgressDto): Response<Unit>

    @POST("Sessions/Playing/Stopped")
    suspend fun reportPlaybackStopped(@Body body: EmbyPlaybackStopDto): Response<Unit>
}
