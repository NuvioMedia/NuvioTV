package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SupportersWallResponseDto(
    val top: SupportersWallGroupDto = SupportersWallGroupDto()
)

@JsonClass(generateAdapter = true)
data class SupportersWallGroupDto(
    val members: List<SupportersWallMemberDto> = emptyList(),
    val totalCount: Int = 0
)

@JsonClass(generateAdapter = true)
data class SupportersWallMemberDto(
    val displayName: String? = null,
    val avatarUrl: String? = null,
    val membershipLevel: String? = null,
    val supporterSince: String? = null
)
