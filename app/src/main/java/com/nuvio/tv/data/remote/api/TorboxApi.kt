package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.TorboxCreateTorrentDataDto
import com.nuvio.tv.data.remote.dto.TorboxCloudItemDto
import com.nuvio.tv.data.remote.dto.TorboxCachedItemDto
import com.nuvio.tv.data.remote.dto.TorboxCheckCachedRequestDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceAuthorizationDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenDto
import com.nuvio.tv.data.remote.dto.TorboxDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.TorboxEnvelopeDto
import com.nuvio.tv.data.remote.dto.TorboxTorrentDataDto
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import retrofit2.http.Query

interface TorboxApi {
    @GET("v1/api/user/me")
    suspend fun getUser(
        @Header("Authorization") authorization: String
    ): Response<ResponseBody>

    @GET("v1/api/user/auth/device/start")
    suspend fun startDeviceAuthorization(
        @Query("app") appName: String
    ): Response<TorboxEnvelopeDto<TorboxDeviceAuthorizationDto>>

    @POST("v1/api/user/auth/device/token")
    suspend fun redeemDeviceAuthorization(
        @Body body: TorboxDeviceTokenRequestDto
    ): Response<TorboxEnvelopeDto<TorboxDeviceTokenDto>>

    @POST("v1/api/torrents/checkcached")
    suspend fun checkCached(
        @Header("Authorization") authorization: String,
        @Query("format") format: String = "object",
        @Body body: TorboxCheckCachedRequestDto
    ): Response<TorboxEnvelopeDto<Map<String, TorboxCachedItemDto>>>

    @Multipart
    @POST("v1/api/torrents/createtorrent")
    suspend fun createTorrent(
        @Header("Authorization") authorization: String,
        @Part("magnet") magnet: RequestBody,
        @Part("add_only_if_cached") addOnlyIfCached: RequestBody,
        @Part("allow_zip") allowZip: RequestBody
    ): Response<TorboxEnvelopeDto<TorboxCreateTorrentDataDto>>

    @GET("v1/api/torrents/mylist")
    suspend fun getTorrent(
        @Header("Authorization") authorization: String,
        @Query("id") id: Int,
        @Query("bypass_cache") bypassCache: Boolean = true
    ): Response<TorboxEnvelopeDto<TorboxTorrentDataDto>>

    @GET("v1/api/torrents/mylist")
    suspend fun listCloudTorrents(
        @Header("Authorization") authorization: String
    ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>>

    @GET("v1/api/usenet/mylist")
    suspend fun listCloudUsenet(
        @Header("Authorization") authorization: String
    ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>>

    @GET("v1/api/webdl/mylist")
    suspend fun listCloudWebDownloads(
        @Header("Authorization") authorization: String
    ): Response<TorboxEnvelopeDto<List<TorboxCloudItemDto>>>

    @GET("v1/api/torrents/requestdl")
    suspend fun requestDownloadLink(
        @Header("Authorization") authorization: String,
        @Query("token") token: String,
        @Query("torrent_id") torrentId: Int,
        @Query("file_id") fileId: Int?,
        @Query("zip_link") zipLink: Boolean = false,
        @Query("redirect") redirect: Boolean = false,
        @Query("append_name") appendName: Boolean = false
    ): Response<TorboxEnvelopeDto<String>>

    @GET("v1/api/torrents/requestdl")
    suspend fun requestCloudTorrentDownloadLink(
        @Header("Authorization") authorization: String,
        @Query("token") token: String,
        @Query("torrent_id") torrentId: String,
        @Query("file_id") fileId: String?,
        @Query("zip_link") zipLink: Boolean = false,
        @Query("redirect") redirect: Boolean = false,
        @Query("append_name") appendName: Boolean = false
    ): Response<TorboxEnvelopeDto<String>>

    @GET("v1/api/usenet/requestdl")
    suspend fun requestCloudUsenetDownloadLink(
        @Header("Authorization") authorization: String,
        @Query("token") token: String,
        @Query("usenet_id") usenetId: String,
        @Query("file_id") fileId: String?,
        @Query("zip_link") zipLink: Boolean = false,
        @Query("redirect") redirect: Boolean = false,
        @Query("append_name") appendName: Boolean = false
    ): Response<TorboxEnvelopeDto<String>>

    @GET("v1/api/webdl/requestdl")
    suspend fun requestCloudWebDownloadLink(
        @Header("Authorization") authorization: String,
        @Query("token") token: String,
        @Query("web_id") webId: String,
        @Query("file_id") fileId: String?,
        @Query("zip_link") zipLink: Boolean = false,
        @Query("redirect") redirect: Boolean = false,
        @Query("append_name") appendName: Boolean = false
    ): Response<TorboxEnvelopeDto<String>>
}
