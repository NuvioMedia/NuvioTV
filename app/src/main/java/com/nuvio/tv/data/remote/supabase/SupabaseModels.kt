package com.nuvio.tv.data.remote.supabase

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class SupabasePlugin(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SupabaseAddon(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    val url: String,
    val name: String? = null,
    val enabled: Boolean = true,
    @SerialName("sort_order") val sortOrder: Int = 0,
    @SerialName("profile_id") val profileId: Int = 1,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)

@Serializable
data class SyncCodeResult(
    val code: String
)

@Serializable
data class ClaimSyncResult(
    @SerialName("result_owner_id") val ownerId: String? = null,
    val success: Boolean,
    val message: String
)

@Serializable
data class SupabaseLinkedDevice(
    val id: String? = null,
    @SerialName("owner_id") val ownerId: String,
    @SerialName("device_user_id") val deviceUserId: String,
    @SerialName("device_name") val deviceName: String? = null,
    @SerialName("linked_at") val linkedAt: String? = null
)

@Serializable
data class TvLoginStartResult(
    val code: String,
    @SerialName("web_url") val webUrl: String,
    @SerialName("expires_at") val expiresAt: String,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int = 3
)

@Serializable
data class TvLoginPollResult(
    val status: String,
    @SerialName("expires_at") val expiresAt: String? = null,
    @SerialName("poll_interval_seconds") val pollIntervalSeconds: Int? = null
)

@Serializable
data class TvLoginExchangeResult(
    @SerialName("access_token") val accessToken: String,
    @SerialName("refresh_token") val refreshToken: String,
    @SerialName("token_type") val tokenType: String? = null,
    @SerialName("expires_in") val expiresIn: Long? = null
)

@Serializable
data class SupabaseWatchProgress(
    val id: String? = null,
    @SerialName("user_id") val userId: String,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    @SerialName("video_id") val videoId: String,
    val season: Int? = null,
    val episode: Int? = null,
    val position: Long,
    val duration: Long,
    @SerialName("last_watched") val lastWatched: Long,
    @SerialName("progress_key") val progressKey: String,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseLibraryItem(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val name: String = "",
    val poster: String? = null,
    @SerialName("poster_shape") val posterShape: String = "POSTER",
    val background: String? = null,
    val description: String? = null,
    @SerialName("release_info") val releaseInfo: String? = null,
    @SerialName("imdb_rating") val imdbRating: Float? = null,
    val genres: List<String> = emptyList(),
    @SerialName("addon_base_url") val addonBaseUrl: String? = null,
    @SerialName("added_at") val addedAt: Long = 0,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseWatchedItem(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("content_id") val contentId: String,
    @SerialName("content_type") val contentType: String,
    val title: String = "",
    val season: Int? = null,
    val episode: Int? = null,
    @SerialName("watched_at") val watchedAt: Long,
    @SerialName("profile_id") val profileId: Int = 1
)

@Serializable
data class SupabaseProfile(
    val id: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("profile_index") val profileIndex: Int,
    val name: String = "",
    @SerialName("avatar_color_hex") val avatarColorHex: String = "#1E88E5",
    @SerialName("uses_primary_addons") val usesPrimaryAddons: Boolean = false,
    @SerialName("uses_primary_plugins") val usesPrimaryPlugins: Boolean = false,
    @SerialName("created_at") val createdAt: String? = null,
    @SerialName("updated_at") val updatedAt: String? = null
)
