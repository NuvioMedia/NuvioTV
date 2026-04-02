package com.nuvio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbyItemDto(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "Type") val type: String? = null,
    @Json(name = "ProviderIds") val providerIds: Map<String, String>? = null,
    @Json(name = "RunTimeTicks") val runTimeTicks: Long? = null,
    @Json(name = "SeriesId") val seriesId: String? = null,
    @Json(name = "ParentIndexNumber") val parentIndexNumber: Int? = null,
    @Json(name = "IndexNumber") val indexNumber: Int? = null,
    @Json(name = "MediaSources") val mediaSources: List<EmbyMediaSourceDto>? = null
)

@JsonClass(generateAdapter = true)
data class EmbyMediaSourceDto(
    @Json(name = "Id") val id: String? = null,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "Path") val path: String? = null
)
