package com.nuvio.tv.data.remote.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.Query

interface TrailerApi {

    @GET("trailer")
    suspend fun getTrailer(
        @Query("youtube_url") youtubeUrl: String,
        @Query("title") title: String? = null,
        @Query("year") year: String? = null
    ): Response<TrailerResponse>
}

@JsonClass(generateAdapter = true)
data class TrailerResponse(
    @Json(name = "url") val url: String? = null,
    @Json(name = "error") val error: String? = null
)
