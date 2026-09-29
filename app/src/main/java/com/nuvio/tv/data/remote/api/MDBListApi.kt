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
    @GET("imdb/{mediaType}/{imdbId}")
    suspend fun getMedia(
        @Path("mediaType") mediaType: String,
        @Path("imdbId") imdbId: String,
        @Query("apikey") apiKey: String,
        @Query("append_to_response") appendToResponse: String = "keyword"
    ): Response<MDBListMediaResponseDto>

    @GET("user")
    suspend fun getUser(
        @Query("apikey") apiKey: String
    ): Response<Unit>

    @POST("imdb/{mediaType}/")
    suspend fun getMediaBatch(
        @Path("mediaType") mediaType: String,
        @Query("apikey") apiKey: String,
        @Body body: MDBListMediaRequestDto
    ): Response<List<MDBListMediaResponseDto>>
}
