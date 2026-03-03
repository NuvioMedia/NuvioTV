package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.BehaviorHintsDto
import com.nuvio.tv.data.remote.dto.ProxyHeadersDto
import com.nuvio.tv.data.remote.dto.StreamDto
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints

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
    addonLogo = addonLogo
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
