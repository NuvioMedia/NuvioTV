package com.nuvio.tv.data.remote.api

import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceCodeResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktDeviceTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktLastActivitiesResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryRemoveResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktPlaybackItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktReorderListsResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRefreshTokenRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktRevokeRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nuvio.tv.data.remote.dto.trakt.TraktScrobbleResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowProgressResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktTokenResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserEpisodeHistoryItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserSettingsResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserStatsResponseDto
import com.nuvio.tv.data.remote.dto.trakt.TraktWatchedMovieItemDto
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.HTTP
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.PUT
import retrofit2.http.Query

interface TraktApi {

    @POST("oauth/device/code")
    suspend fun requestDeviceCode(
        @Body body: TraktDeviceCodeRequestDto
    ): Response<TraktDeviceCodeResponseDto>

    @POST("oauth/device/token")
    suspend fun requestDeviceToken(
        @Body body: TraktDeviceTokenRequestDto
    ): Response<TraktTokenResponseDto>

    @POST("oauth/token")
    suspend fun refreshToken(
        @Body body: TraktRefreshTokenRequestDto
    ): Response<TraktTokenResponseDto>

    @POST("oauth/revoke")
    suspend fun revokeToken(
        @Body body: TraktRevokeRequestDto
    ): Response<Unit>

    @GET("users/settings")
    suspend fun getUserSettings(
        @Header("Authorization") authorization: String
    ): Response<TraktUserSettingsResponseDto>

    @GET("users/{id}/stats")
    suspend fun getUserStats(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<TraktUserStatsResponseDto>

    @POST("scrobble/start")
    suspend fun scrobbleStart(
        @Header("Authorization") authorization: String,
        @Body body: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @POST("scrobble/stop")
    suspend fun scrobbleStop(
        @Header("Authorization") authorization: String,
        @Body body: TraktScrobbleRequestDto
    ): Response<TraktScrobbleResponseDto>

    @GET("sync/last_activities")
    suspend fun getLastActivities(
        @Header("Authorization") authorization: String
    ): Response<TraktLastActivitiesResponseDto>

    @GET("sync/playback/{type}")
    suspend fun getPlayback(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Query("start_at") startAt: String? = null,
        @Query("end_at") endAt: String? = null
    ): Response<List<TraktPlaybackItemDto>>

    @GET("sync/watched/{type}")
    suspend fun getWatched(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Query("extended") extended: String? = null
    ): Response<List<TraktWatchedMovieItemDto>>

    @GET("sync/history/episodes")
    suspend fun getEpisodeHistory(
        @Header("Authorization") authorization: String,
        @Query("page") page: Int = 1,
        @Query("limit") limit: Int = 100,
        @Query("start_at") startAt: String? = null,
        @Query("end_at") endAt: String? = null
    ): Response<List<TraktUserEpisodeHistoryItemDto>>

    @POST("sync/history")
    suspend fun addHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktHistoryAddRequestDto
    ): Response<TraktHistoryAddResponseDto>

    @GET("sync/history/{type}/{id}")
    suspend fun getHistoryById(
        @Header("Authorization") authorization: String,
        @Path("type") type: String,
        @Path("id") id: String
    ): Response<List<TraktHistoryItemDto>>

    @GET("shows/{id}/progress/watched")
    suspend fun getShowProgressWatched(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Query("hidden") hidden: Boolean = false,
        @Query("specials") specials: Boolean = false,
        @Query("count_specials") countSpecials: Boolean = false
    ): Response<TraktShowProgressResponseDto>

    @DELETE("sync/playback/{id}")
    suspend fun deletePlayback(
        @Header("Authorization") authorization: String,
        @Path("id") playbackId: Long
    ): Response<Unit>

    @HTTP(method = "POST", path = "sync/history/remove", hasBody = true)
    suspend fun removeHistory(
        @Header("Authorization") authorization: String,
        @Body body: TraktHistoryRemoveRequestDto
    ): Response<TraktHistoryRemoveResponseDto>

    @GET("users/{id}/lists")
    suspend fun getUserLists(
        @Header("Authorization") authorization: String,
        @Path("id") id: String
    ): Response<List<TraktListSummaryDto>>

    @POST("users/{id}/lists")
    suspend fun createUserList(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>

    @PUT("users/{id}/lists/{list_id}")
    suspend fun updateUserList(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("list_id") listId: String,
        @Body body: TraktCreateOrUpdateListRequestDto
    ): Response<TraktListSummaryDto>

    @DELETE("users/{id}/lists/{list_id}")
    suspend fun deleteUserList(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("list_id") listId: String
    ): Response<Unit>

    @POST("users/{id}/lists/reorder")
    suspend fun reorderUserLists(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Body body: TraktReorderListsRequestDto
    ): Response<TraktReorderListsResponseDto>

    @GET("users/{id}/lists/{list_id}/items/{type}")
    suspend fun getUserListItems(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("list_id") listId: String,
        @Path("type") type: String
    ): Response<List<TraktListItemDto>>

    @POST("users/{id}/lists/{list_id}/items")
    suspend fun addUserListItems(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("list_id") listId: String,
        @Body body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>

    @POST("users/{id}/lists/{list_id}/items/remove")
    suspend fun removeUserListItems(
        @Header("Authorization") authorization: String,
        @Path("id") id: String,
        @Path("list_id") listId: String,
        @Body body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>

    @GET("sync/watchlist/{type}")
    suspend fun getWatchlist(
        @Header("Authorization") authorization: String,
        @Path("type") type: String
    ): Response<List<TraktListItemDto>>

    @POST("sync/watchlist")
    suspend fun addToWatchlist(
        @Header("Authorization") authorization: String,
        @Body body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>

    @POST("sync/watchlist/remove")
    suspend fun removeFromWatchlist(
        @Header("Authorization") authorization: String,
        @Body body: TraktListItemsMutationRequestDto
    ): Response<TraktListItemsMutationResponseDto>
}
