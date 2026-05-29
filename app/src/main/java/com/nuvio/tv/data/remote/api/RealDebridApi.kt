package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.RealDebridAddTorrentDto
import com.nuvio.tv.data.remote.dto.RealDebridTorrentInfoDto
import com.nuvio.tv.data.remote.dto.RealDebridUnrestrictLinkDto
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.DELETE
import retrofit2.http.Field
import retrofit2.http.FormUrlEncoded
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Path

interface RealDebridApi {
    @GET("user")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("torrents/addMagnet")
    suspend fun addMagnet(
        @Header("Authorization") authorization: String,
        @Field("magnet") magnet: String
    ): Response<RealDebridAddTorrentDto>

    @GET("torrents/info/{id}")
    suspend fun getTorrentInfo(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<RealDebridTorrentInfoDto>

    @FormUrlEncoded
    @POST("torrents/selectFiles/{id}")
    suspend fun selectFiles(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Field("files") files: String
    ): Response<ResponseBody>

    @FormUrlEncoded
    @POST("unrestrict/link")
    suspend fun unrestrictLink(
        @Header("Authorization") authorization: String,
        @Field("link") link: String
    ): Response<RealDebridUnrestrictLinkDto>

    @DELETE("torrents/delete/{id}")
    suspend fun deleteTorrent(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<ResponseBody>
}
