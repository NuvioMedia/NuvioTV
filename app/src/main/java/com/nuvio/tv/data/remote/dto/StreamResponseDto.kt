package com.nuvio.tv.data.remote.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class StreamResponseDto(
    @Json(name = "streams") val streams: List<StreamDto>? = null
)

@JsonClass(generateAdapter = true)
data class StreamDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "description") val description: String? = null,
    @Json(name = "url") val url: String? = null,
    @Json(name = "ytId") val ytId: String? = null,
    @Json(name = "infoHash") val infoHash: String? = null,
    @Json(name = "fileIdx") val fileIdx: Int? = null,
    @Json(name = "externalUrl") val externalUrl: String? = null,
    @Json(name = "behaviorHints") val behaviorHints: BehaviorHintsDto? = null,
    @Json(name = "sources") val sources: List<String>? = null,
    @Json(name = "subtitles") val subtitles: List<SubtitleDto>? = null,
    @Json(name = "clientResolve") val clientResolve: StreamClientResolveDto? = null
)

@JsonClass(generateAdapter = true)
data class StreamClientResolveDto(
    @Json(name = "type") val type: String? = null,
    @Json(name = "infoHash") val infoHash: String? = null,
    @Json(name = "fileIdx") val fileIdx: Int? = null,
    @Json(name = "magnetUri") val magnetUri: String? = null,
    @Json(name = "sources") val sources: List<String>? = null,
    @Json(name = "torrentName") val torrentName: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "mediaType") val mediaType: String? = null,
    @Json(name = "mediaId") val mediaId: String? = null,
    @Json(name = "mediaOnlyId") val mediaOnlyId: String? = null,
    @Json(name = "title") val title: String? = null,
    @Json(name = "season") val season: Int? = null,
    @Json(name = "episode") val episode: Int? = null,
    @Json(name = "service") val service: String? = null,
    @Json(name = "serviceIndex") val serviceIndex: Int? = null,
    @Json(name = "serviceExtension") val serviceExtension: String? = null,
    @Json(name = "isCached") val isCached: Boolean? = null,
    @Json(name = "stream") val stream: StreamClientResolveStreamDto? = null
)

@JsonClass(generateAdapter = true)
data class StreamClientResolveStreamDto(
    @Json(name = "raw") val raw: StreamClientResolveRawDto? = null
)

@JsonClass(generateAdapter = true)
data class StreamClientResolveRawDto(
    @Json(name = "torrentName") val torrentName: String? = null,
    @Json(name = "filename") val filename: String? = null,
    @Json(name = "size") val size: Long? = null,
    @Json(name = "folderSize") val folderSize: Long? = null,
    @Json(name = "tracker") val tracker: String? = null,
    @Json(name = "indexer") val indexer: String? = null,
    @Json(name = "network") val network: String? = null,
    @Json(name = "parsed") val parsed: StreamClientResolveParsedDto? = null
)

@JsonClass(generateAdapter = true)
data class StreamClientResolveParsedDto(
    @Json(name = "raw_title") val rawTitle: String? = null,
    @Json(name = "parsed_title") val parsedTitle: String? = null,
    @Json(name = "year") val year: Int? = null,
    @Json(name = "resolution") val resolution: String? = null,
    @Json(name = "seasons") val seasons: List<Int>? = null,
    @Json(name = "episodes") val episodes: List<Int>? = null,
    @Json(name = "quality") val quality: String? = null,
    @Json(name = "hdr") val hdr: List<String>? = null,
    @Json(name = "codec") val codec: String? = null,
    @Json(name = "audio") val audio: List<String>? = null,
    @Json(name = "channels") val channels: List<String>? = null,
    @Json(name = "languages") val languages: List<String>? = null,
    @Json(name = "group") val group: String? = null,
    @Json(name = "network") val network: String? = null,
    @Json(name = "edition") val edition: String? = null,
    @Json(name = "duration") val duration: Long? = null,
    @Json(name = "bit_depth") val bitDepth: String? = null,
    @Json(name = "extended") val extended: Boolean? = null,
    @Json(name = "theatrical") val theatrical: Boolean? = null,
    @Json(name = "remastered") val remastered: Boolean? = null,
    @Json(name = "unrated") val unrated: Boolean? = null
)

@JsonClass(generateAdapter = true)
data class BehaviorHintsDto(
    @Json(name = "notWebReady") val notWebReady: Boolean? = null,
    @Json(name = "bingeGroup") val bingeGroup: String? = null,
    @Json(name = "countryWhitelist") val countryWhitelist: List<String>? = null,
    @Json(name = "proxyHeaders") val proxyHeaders: ProxyHeadersDto? = null,
    @Json(name = "videoHash") val videoHash: String? = null,
    @Json(name = "videoSize") val videoSize: Long? = null,
    @Json(name = "filename") val filename: String? = null
)

@JsonClass(generateAdapter = true)
data class ProxyHeadersDto(
    @Json(name = "request") val request: Map<String, String>? = null,
    @Json(name = "response") val response: Map<String, String>? = null
)

@JsonClass(generateAdapter = true)
data class SubtitleDto(
    @Json(name = "id") val id: String? = null,
    @Json(name = "url") val url: String,
    @Json(name = "lang") val lang: String
)
