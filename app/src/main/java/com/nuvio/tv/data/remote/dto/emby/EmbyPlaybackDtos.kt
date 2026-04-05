package com.nuvio.tv.data.remote.dto.emby

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class EmbyPlaybackStartDto(
    @Json(name = "ItemId") val itemId: String,
    @Json(name = "MediaSourceId") val mediaSourceId: String,
    @Json(name = "PlaySessionId") val playSessionId: String,
    @Json(name = "PositionTicks") val positionTicks: Long = 0,
    @Json(name = "CanSeek") val canSeek: Boolean = true,
    @Json(name = "IsPaused") val isPaused: Boolean = false,
    @Json(name = "PlayMethod") val playMethod: String = "DirectStream",
    @Json(name = "QueueableMediaTypes") val queueableMediaTypes: List<String> = listOf("Video")
)

@JsonClass(generateAdapter = true)
data class EmbyPlaybackProgressDto(
    @Json(name = "ItemId") val itemId: String,
    @Json(name = "MediaSourceId") val mediaSourceId: String,
    @Json(name = "PlaySessionId") val playSessionId: String,
    @Json(name = "PositionTicks") val positionTicks: Long = 0,
    @Json(name = "CanSeek") val canSeek: Boolean = true,
    @Json(name = "IsPaused") val isPaused: Boolean = false,
    @Json(name = "PlayMethod") val playMethod: String = "DirectStream",
    @Json(name = "EventName") val eventName: String = "TimeUpdate"
)

@JsonClass(generateAdapter = true)
data class EmbyPlaybackStopDto(
    @Json(name = "ItemId") val itemId: String,
    @Json(name = "MediaSourceId") val mediaSourceId: String,
    @Json(name = "PlaySessionId") val playSessionId: String,
    @Json(name = "PositionTicks") val positionTicks: Long = 0
)
