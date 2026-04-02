package com.nuvio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbyItemsResponseDto(
    @Json(name = "Items") val items: List<EmbyItemDto> = emptyList(),
    @Json(name = "TotalRecordCount") val totalRecordCount: Int = 0
)
