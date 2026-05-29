package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.ProxyHeadersDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveParsedDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveRawDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveStreamDto
import com.nuvio.tv.data.remote.dto.StreamClientResolveDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.model.StreamClientResolve
import com.nuvio.tv.domain.model.StreamClientResolveParsed
import com.nuvio.tv.domain.model.StreamClientResolveRaw
import com.nuvio.tv.domain.model.StreamClientResolveStream

fun StreamDto.toDomain(addonName: String, addonLogo: String?): Stream = Stream(
    name = name,
    title = title,
    description = description,
    url = url,
    ytId = ytId,
    infoHash = infoHash,
    fileIdx = fileIdx,
    externalUrl = externalUrl,
    behaviorHints = behaviorHints?.toDomain(),
    addonName = addonName,
    addonLogo = addonLogo,
    sources = sources,
    clientResolve = clientResolve?.toDomain()
)

fun StreamClientResolveDto.toDomain(): StreamClientResolve = StreamClientResolve(
    type = type,
    infoHash = infoHash,
    fileIdx = fileIdx,
    magnetUri = magnetUri,
    sources = sources,
    torrentName = torrentName,
    filename = filename,
    mediaType = mediaType,
    mediaId = mediaId,
    mediaOnlyId = mediaOnlyId,
    title = title,
    season = season,
    episode = episode,
    service = service,
    serviceIndex = serviceIndex,
    serviceExtension = serviceExtension,
    isCached = isCached,
    stream = stream?.toDomain()
)

fun StreamClientResolveStreamDto.toDomain(): StreamClientResolveStream = StreamClientResolveStream(
    raw = raw?.toDomain()
)

fun StreamClientResolveRawDto.toDomain(): StreamClientResolveRaw = StreamClientResolveRaw(
    torrentName = torrentName,
    filename = filename,
    size = size,
    folderSize = folderSize,
    tracker = tracker,
    indexer = indexer,
    network = network,
    parsed = parsed?.toDomain()
)

fun StreamClientResolveParsedDto.toDomain(): StreamClientResolveParsed = StreamClientResolveParsed(
    rawTitle = rawTitle,
    parsedTitle = parsedTitle,
    year = year,
    resolution = resolution,
    seasons = seasons,
    episodes = episodes,
    quality = quality,
    hdr = hdr,
    codec = codec,
    audio = audio,
    channels = channels,
    languages = languages,
    group = group,
    network = network,
    edition = edition,
    duration = duration,
    bitDepth = bitDepth,
    extended = extended,
    theatrical = theatrical,
    remastered = remastered,
    unrated = unrated
)

fun BehaviorHintsDto.toDomain(): StreamBehaviorHints = StreamBehaviorHints(
    notWebReady = notWebReady,
    bingeGroup = bingeGroup,
    countryWhitelist = countryWhitelist,
    proxyHeaders = proxyHeaders?.toDomain(),
    videoHash = videoHash,
    videoSize = videoSize,
    filename = filename
)

fun ProxyHeadersDto.toDomain(): ProxyHeaders = ProxyHeaders(
    request = sanitizeHeaderMap(request),
    response = sanitizeHeaderMap(response)
)

private fun sanitizeHeaderMap(headers: Map<String, String>?): Map<String, String>? {
    if (headers == null) return null
    val raw: Map<*, *> = headers
    if (raw.isEmpty()) return null

    val sanitized = LinkedHashMap<String, String>(raw.size)
    raw.forEach { (rawKey, rawValue) ->
        val key = (rawKey as? String)?.trim().orEmpty()
        val value = (rawValue as? String)?.trim().orEmpty()
        if (key.isEmpty() || value.isEmpty()) return@forEach
        if (key.equals("Range", ignoreCase = true)) return@forEach
        sanitized[key] = value
    }
    return sanitized.takeIf { it.isNotEmpty() }
}
