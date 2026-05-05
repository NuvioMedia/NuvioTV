package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktListIdsDto(
    @Json(name = "trakt") val trakt: Long? = null,
    @Json(name = "slug") val slug: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktListSummaryDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "privacy") val privacy: String? = null,
    @Json(name = "share_link") val shareLink: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "display_numbers") val displayNumbers: Boolean? = null,
    @Json(name = "allow_comments") val allowComments: Boolean? = null,
    @Json(name = "sort_by") val sortBy: String? = null,
    @Json(name = "sort_how") val sortHow: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "likes") val likes: Int? = null,
    @Json(name = "created_at") val createdAt: String? = null,
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "ids") val ids: TraktListIdsDto? = null,
    @Json(name = "user") val user: TraktUserDto? = null,
    @Json(name = "images") val images: TraktListImagesDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListImagesDto(
    @Json(name = "posters") val posters: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class TraktProminentListDto(
    @Json(name = "like_count") val likeCount: Int? = null,
    @Json(name = "comment_count") val commentCount: Int? = null,
    @Json(name = "list") val list: TraktListSummaryDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktCreateOrUpdateListRequestDto(
    @Json(name = "name") val name: String,
    @Json(name = "description") val description: String? = null,
    @Json(name = "privacy") val privacy: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktReorderListsRequestDto(
    @Json(name = "rank") val rank: List<Long>
)

@JsonClass(generateAdapter = true)
data class TraktReorderListsResponseDto(
    @Json(name = "updated") val updated: Int? = null,
    @Json(name = "skipped_ids") val skippedIds: List<Long>? = null
)

@JsonClass(generateAdapter = true)
data class TraktListItemDto(
    @Json(name = "rank") val rank: Int? = null,
    @Json(name = "id") val id: Long? = null,
    @Json(name = "listed_at") val listedAt: String? = null,
    @Json(name = "notes") val notes: String? = null,
    @Json(name = "type") val type: String? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "season") val season: TraktListSeasonDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null,
    @Json(name = "person") val person: TraktListPersonDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListSeasonDto(
    @Json(name = "number") val number: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListPersonDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListItemsMutationRequestDto(
    @Json(name = "movies") val movies: List<TraktListMovieRequestItemDto>? = null,
    @Json(name = "shows") val shows: List<TraktListShowRequestItemDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktListMovieRequestItemDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListShowRequestItemDto(
    @Json(name = "title") val title: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListItemsMutationResponseDto(
    @Json(name = "added") val added: TraktListMutationCountDto? = null,
    @Json(name = "existing") val existing: TraktListMutationCountDto? = null,
    @Json(name = "deleted") val deleted: TraktListMutationCountDto? = null,
    @Json(name = "not_found") val notFound: TraktListMutationNotFoundDto? = null,
    @Json(name = "list") val list: TraktListMutationSummaryDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktListMutationCountDto(
    @Json(name = "movies") val movies: Int? = null,
    @Json(name = "shows") val shows: Int? = null,
    @Json(name = "seasons") val seasons: Int? = null,
    @Json(name = "episodes") val episodes: Int? = null,
    @Json(name = "people") val people: Int? = null
)

@JsonClass(generateAdapter = true)
data class TraktListMutationNotFoundDto(
    @Json(name = "movies") val movies: List<TraktMovieDto>? = null,
    @Json(name = "shows") val shows: List<TraktShowDto>? = null,
    @Json(name = "seasons") val seasons: List<TraktListSeasonDto>? = null,
    @Json(name = "episodes") val episodes: List<TraktEpisodeDto>? = null,
    @Json(name = "people") val people: List<TraktListPersonDto>? = null
)

@JsonClass(generateAdapter = true)
data class TraktListMutationSummaryDto(
    @Json(name = "updated_at") val updatedAt: String? = null,
    @Json(name = "item_count") val itemCount: Int? = null
)
