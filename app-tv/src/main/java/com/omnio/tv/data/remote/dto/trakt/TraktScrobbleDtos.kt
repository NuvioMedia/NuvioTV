package com.omnio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktScrobbleRequestDto(
    @Json(name = "movie") val movie: TraktMovieDto? = null,
    @Json(name = "show") val show: TraktShowDto? = null,
    @Json(name = "episode") val episode: TraktEpisodeDto? = null,
    @Json(name = "progress") val progress: Float,
    @Json(name = "app_version") val appVersion: String? = null
)

@JsonClass(generateAdapter = true)
data class TraktScrobbleResponseDto(
    @Json(name = "action") val action: String? = null
)

