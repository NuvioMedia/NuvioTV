package com.nuvio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbyUserDto(
    @Json(name = "Id") val id: String,
    @Json(name = "Name") val name: String? = null,
    @Json(name = "HasPassword") val hasPassword: Boolean = false,
    @Json(name = "Policy") val policy: EmbyUserPolicyDto? = null
)

@JsonClass(generateAdapter = true)
data class EmbyUserPolicyDto(
    @Json(name = "IsAdministrator") val isAdministrator: Boolean = false
)
