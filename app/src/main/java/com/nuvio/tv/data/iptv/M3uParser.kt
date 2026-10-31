package com.nuvio.tv.data.iptv

import java.io.BufferedReader

object M3uParser {

    data class Result(
        val channels: List<IptvChannel>,
        val epgUrls: List<String>
    )

    private val attributeRegex = Regex("""([A-Za-z0-9_\-]+)\s*=\s*"([^"]*)"""")
    private val vodPathRegex = Regex("""/(movie|series)/[^/]+/[^/]+/[^/]+$""", RegexOption.IGNORE_CASE)

    fun parse(
        reader: BufferedReader,
        playlistId: String,
        defaultUserAgent: String? = null,
        skipVod: Boolean = true
    ): Result {
        val channels = ArrayList<IptvChannel>()
        val epgUrls = LinkedHashSet<String>()
        val usedKeys = HashMap<String, Int>()

        var pendingInfo: String? = null
        var pendingGroup: String? = null
        var pendingHeaders = LinkedHashMap<String, String>()
        var index = 0

        fun reset() {
            pendingInfo = null
            pendingGroup = null
            pendingHeaders = LinkedHashMap()
        }

        var firstLine = true
        while (true) {
            val raw = reader.readLine() ?: break
            val line = if (firstLine) raw.removePrefix("﻿").trim() else raw.trim()
            firstLine = false
            if (line.isEmpty()) continue

            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> {
                    val attrs = parseAttributes(line)
                    listOf("url-tvg", "x-tvg-url", "tvg-url").forEach { key ->
                        attrs[key]?.split(',')
                            ?.map { it.trim() }
                            ?.filter { it.startsWith("http", ignoreCase = true) }
                            ?.forEach { epgUrls.add(it) }
                    }
                }
                line.startsWith("#EXTINF", ignoreCase = true) -> {
                    reset()
                    pendingInfo = line
                }
                line.startsWith("#EXTGRP:", ignoreCase = true) -> {
                    pendingGroup = line.substringAfter(':').trim().takeIf { it.isNotEmpty() }
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val option = line.substringAfter(':')
                    val name = option.substringBefore('=').trim().lowercase()
                    val value = option.substringAfter('=', "").trim()
                    if (value.isNotEmpty()) {
                        when (name) {
                            "http-user-agent" -> pendingHeaders["User-Agent"] = value
                            "http-referrer", "http-referer" -> pendingHeaders["Referer"] = value
                            "http-origin" -> pendingHeaders["Origin"] = value
                        }
                    }
                }
                line.startsWith("#") -> Unit
                else -> {
                    val info = pendingInfo
                    if (info != null) {
                        val channel = buildChannel(
                            info = info,
                            urlLine = line,
                            playlistId = playlistId,
                            fallbackGroup = pendingGroup,
                            extraHeaders = pendingHeaders,
                            defaultUserAgent = defaultUserAgent,
                            index = index,
                            usedKeys = usedKeys
                        )
                        if (channel != null && !(skipVod && vodPathRegex.containsMatchIn(channel.streamUrl.substringBefore('?')))) {
                            channels.add(channel)
                            index++
                        }
                    }
                    reset()
                }
            }
        }
        return Result(channels = channels, epgUrls = epgUrls.toList())
    }

    internal fun parseAttributes(line: String): Map<String, String> {
        val result = HashMap<String, String>()
        attributeRegex.findAll(line).forEach { match ->
            result[match.groupValues[1].lowercase()] = match.groupValues[2].trim()
        }
        return result
    }

    internal fun splitInfo(info: String): Pair<String, String> {
        var inQuotes = false
        val body = info.substringAfter(':', info)
        for (i in body.indices) {
            val c = body[i]
            if (c == '"') inQuotes = !inQuotes
            if (c == ',' && !inQuotes) {
                return body.substring(0, i) to body.substring(i + 1).trim()
            }
        }
        return body to ""
    }

    private fun buildChannel(
        info: String,
        urlLine: String,
        playlistId: String,
        fallbackGroup: String?,
        extraHeaders: Map<String, String>,
        defaultUserAgent: String?,
        index: Int,
        usedKeys: HashMap<String, Int>
    ): IptvChannel? {
        val (attrPart, title) = splitInfo(info)
        val attrs = parseAttributes(attrPart)
        val headers = LinkedHashMap<String, String>()
        if (!defaultUserAgent.isNullOrBlank()) headers["User-Agent"] = defaultUserAgent

        var url = urlLine
        val pipeIndex = urlLine.indexOf('|')
        if (pipeIndex > 0) {
            url = urlLine.substring(0, pipeIndex).trim()
            urlLine.substring(pipeIndex + 1).split('&').forEach { part ->
                val name = part.substringBefore('=').trim()
                val value = part.substringAfter('=', "").trim()
                if (name.isNotEmpty() && value.isNotEmpty()) {
                    headers[normalizeHeaderName(name)] = runCatching {
                        java.net.URLDecoder.decode(value, "UTF-8")
                    }.getOrDefault(value)
                }
            }
        }
        headers.putAll(extraHeaders)
        attrs["http-user-agent"]?.takeIf { it.isNotBlank() }?.let { headers["User-Agent"] = it }
        attrs["http-referrer"]?.takeIf { it.isNotBlank() }?.let { headers["Referer"] = it }
        if (url.isBlank() || !url.contains("://")) return null

        val tvgName = attrs["tvg-name"]?.takeIf { it.isNotBlank() }
        val name = title.ifBlank { tvgName ?: url.substringAfterLast('/') }
        val tvgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() }
        val group = attrs["group-title"]?.takeIf { it.isNotBlank() }
            ?: fallbackGroup
            ?: ""
        val number = attrs["tvg-chno"]?.toIntOrNull()
            ?: attrs["channel-number"]?.toIntOrNull()
            ?: attrs["tvg-num"]?.toIntOrNull()
            ?: (index + 1)
        val logo = (attrs["tvg-logo"] ?: attrs["logo"])?.takeIf { it.startsWith("http", ignoreCase = true) }
        val catchupDays = attrs["catchup-days"]?.toIntOrNull()
            ?: attrs["tvg-rec"]?.toIntOrNull()
            ?: 0

        val baseKey = "$playlistId|${tvgId ?: ""}|$name"
        val seen = usedKeys[baseKey] ?: 0
        usedKeys[baseKey] = seen + 1
        val key = if (seen == 0) baseKey else "$baseKey#$seen"

        return IptvChannel(
            key = key,
            playlistId = playlistId,
            name = name,
            number = number,
            logo = logo,
            group = group,
            tvgId = tvgId,
            tvgName = tvgName,
            streamUrl = url,
            headers = headers,
            catchupDays = catchupDays
        )
    }

    private fun normalizeHeaderName(name: String): String = when (name.lowercase()) {
        "user-agent" -> "User-Agent"
        "referer", "referrer" -> "Referer"
        "origin" -> "Origin"
        "cookie" -> "Cookie"
        else -> name
    }
}
