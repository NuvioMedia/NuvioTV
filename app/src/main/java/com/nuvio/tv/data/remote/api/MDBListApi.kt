package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaResponseDto
import com.nuvio.tv.data.remote.dto.mdblist.MDBListMediaRequestDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Query

interface MDBListApi {
    @GET("{provider}/{mediaType}/{mediaId}")
    suspend fun getMedia(
        @Path("provider") provider: String,
        @Path("mediaType") mediaType: String,
        @Path("mediaId") mediaId: String,
        @Query("apikey") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "keyword"
    ): Response<MDBListMediaResponseDto>

    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<Unit>

    @POST("{provider}/{mediaType}/")
    suspend fun getMediaBatch(
        @Path("provider") provider: String,
        @Path("mediaType") mediaType: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListMediaRequestDto
    ): Response<List<MDBListMediaResponseDto>>
}
