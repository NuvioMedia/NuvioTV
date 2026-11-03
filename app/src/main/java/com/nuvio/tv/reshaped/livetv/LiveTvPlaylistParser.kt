package com.nuvio.tv.reshaped.livetv

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal data class ParsedM3uPlaylist(
    val channels: List<LiveTvChannel>,
    val epgUrls: List<String>,
    /** The link was an HLS stream itself (a single channel), not a channel list. */
    val isHlsStream: Boolean = false,
)

/**
 * Parses an M3U playlist line by line, so a large playlist is read from the network or a file
 * without ever sitting in memory as one string. Duplicate links and "#### Category ####"
 * separator entries are dropped.
 */
internal fun parseM3uPlaylist(lines: Sequence<String>, baseUrl: String? = null): ParsedM3uPlaylist {
    val base = baseUrl?.takeIf { it.isHttpUrl() }?.toHttpUrlOrNull()
    // A link that is not a playlist (a stream, a web page) is given up on early, not read forever.
    var linesRead = 0
    var sawPlaylistTag = false
    var pendingGroup: String? = null
    val channels = ArrayList<LiveTvChannel>()
    val seenUrls = HashSet<String>()
    val epgUrls = LinkedHashSet<String>()
    // Thousands of channels share a few groups and header sets: each is kept once.
    val groups = HashMap<String, String>()
    val headerSets = HashMap<Map<String, String>, Map<String, String>>()
    var metadata: M3uMetadata? = null
    var pendingHeaders = emptyMap<String, String>()
    var isHlsStream = false
    // Catch-up the playlist header gives every channel, and one shared instance per kind.
    var defaultCatchup: Map<String, String> = emptyMap()
    // The header's `tvg-shift`: every channel's, unless it gives its own.
    var defaultShift = 0
    val catchups = HashMap<LiveTvCatchup, LiveTvCatchup>()

    for (rawLine in lines) {
        val line = rawLine.trim().removePrefix("﻿")
        if (!sawPlaylistTag && channels.isEmpty() && ++linesRead > NOT_A_PLAYLIST_LINES) break
        when {
            line.isEmpty() -> Unit
            line.startsWith("#EXTM3U", ignoreCase = true) -> {
                sawPlaylistTag = true
                val attributes = parseM3uAttributes(line)
                defaultCatchup = attributes.filterKeys { it in CATCHUP_ATTRIBUTES }
                defaultShift = m3uGuideShiftMinutes(attributes["tvg-shift"]) ?: 0
                listOfNotNull(attributes["url-tvg"], attributes["x-tvg-url"], attributes["tvg-url"])
                    .flatMap { it.split(',', ';') }
                    .map(String::trim)
                    .filter { it.isHttpUrl() }
                    .forEach(epgUrls::add)
            }
            line.startsWith("#EXT-X-", ignoreCase = true) -> {
                // HLS tags before any channel: this is a stream's own playlist, and its "entries"
                // are video segments. One stray tag inside a channel list is ignored.
                if (channels.isEmpty() && metadata == null) {
                    isHlsStream = true
                    break
                }
            }
            line.startsWith("#EXTINF", ignoreCase = true) -> {
                sawPlaylistTag = true
                metadata = parseExtInf(line)
            }
            line.startsWith("#EXTGRP:", ignoreCase = true) -> pendingGroup = line.substringAfter(':').trim()
            line.startsWith("#EXTVLCOPT:http-user-agent=", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + ("User-Agent" to line.substringAfter('=').trim())
            line.startsWith("#EXTVLCOPT:http-referrer=", ignoreCase = true) ||
                line.startsWith("#EXTVLCOPT:http-referer=", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + ("Referer" to line.substringAfter('=').trim())
            line.startsWith("#EXTVLCOPT:http-origin=", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + ("Origin" to line.substringAfter('=').trim())
            line.startsWith("#EXTHTTP:", ignoreCase = true) ->
                pendingHeaders = pendingHeaders + parseExtHttpHeaders(line.substringAfter(':'))
            line.startsWith("#") -> Unit
            else -> {
                val current = metadata
                metadata = null
                val headers = pendingHeaders
                pendingHeaders = emptyMap()
                val extGroup = pendingGroup
                pendingGroup = null
                // Only links: web page or binary lines (a link that is not a playlist) are not channels.
                val written = line.substringBefore('|').trim()
                val url = when {
                    "://" in written -> written
                    current != null && base != null -> base.resolve(written)?.toString() ?: continue
                    else -> continue
                }
                if (url.isEmpty() || !seenUrls.add(url)) continue
                val name = current?.name?.takeIf(String::isNotBlank) ?: "Channel ${channels.size + 1}"
                if (isLikelyCategoryHeading(name)) continue
                val extraHeaders = headers + parseUrlHeaders(line)
                val defaults = defaultStreamHeaders(url)
                val group = current?.group?.takeIf(String::isNotBlank) ?: extGroup.orEmpty()
                channels += LiveTvChannel(
                    id = "m${channels.size}",
                    name = name,
                    streamUrl = url,
                    tvgId = current?.tvgId,
                    logoUrl = current?.logoUrl,
                    tvgName = current?.tvgName?.takeIf { it != name },
                    catchup = m3uCatchup(current?.catchup.orEmpty(), defaultCatchup)?.let { catchups.getOrPut(it) { it } },
                    guideShiftMinutes = current?.guideShiftMinutes ?: defaultShift,
                    group = groups.getOrPut(group) { group },
                    headers = if (extraHeaders.isEmpty()) {
                        defaults
                    } else {
                        (defaults + extraHeaders).let { headerSets.getOrPut(it) { it } }
                    },
                )
            }
        }
    }
    if (isHlsStream) return ParsedM3uPlaylist(channels = emptyList(), epgUrls = emptyList(), isHlsStream = true)
    channels.trimToSize()
    return ParsedM3uPlaylist(channels = channels, epgUrls = epgUrls.toList())
}

/** Lines read without any playlist tag or channel before a link counts as "not a playlist". */
private const val NOT_A_PLAYLIST_LINES = 2_000

private class M3uMetadata(
    val name: String,
    val tvgId: String?,
    val tvgName: String?,
    val logoUrl: String?,
    val group: String,
    /** The entry's catch-up attributes, usually none. */
    val catchup: Map<String, String>,
    /** The entry's own `tvg-shift`, when it gives one. */
    val guideShiftMinutes: Int? = null,
)

/**
 * `tvg-shift` in minutes: hours, decimals allowed ("-4.5"), as IPTV players read it; a value
 * beyond a day is not a time zone and is ignored.
 */
internal fun m3uGuideShiftMinutes(value: String?): Int? {
    val hours = value?.trim()?.replace(',', '.')?.toDoubleOrNull() ?: return null
    if (hours.isNaN() || hours < -24.0 || hours > 24.0) return null
    return Math.round(hours * 60).toInt()
}

private val CATCHUP_ATTRIBUTES = setOf("catchup", "catchup-type", "catchup-days", "catchup-source", "tvg-rec", "timeshift")

/**
 * A channel's catch-up from its `catchup*` attributes (falling back on the playlist header's),
 * as IPTV players read them: `catchup`/`catchup-type` the kind, `catchup-days` (or `tvg-rec`,
 * `timeshift`) how far back, `catchup-source` the link template.
 */
internal fun m3uCatchup(entry: Map<String, String>, playlist: Map<String, String>): LiveTvCatchup? {
    if (entry.isEmpty() && playlist.isEmpty()) return null
    fun value(name: String) = entry[name]?.takeIf(String::isNotBlank) ?: playlist[name]?.takeIf(String::isNotBlank)
    val type = (value("catchup") ?: value("catchup-type"))?.trim()?.lowercase()
    val days = (value("catchup-days") ?: value("tvg-rec") ?: value("timeshift"))?.trim()?.toIntOrNull()
    val template = value("catchup-source")?.trim()
    if (type == null && (days ?: 0) <= 0) return null
    if (type == "disabled" || type == "none" || type == "0" || days == 0) return null
    val kind = when (type) {
        null, "default", "1" -> if (template == null) LiveTvCatchup.Kind.Shift else LiveTvCatchup.Kind.Default
        "append" -> LiveTvCatchup.Kind.Append
        "shift", "timeshift" -> LiveTvCatchup.Kind.Shift
        "flussonic", "flussonic-hls", "flussonic-ts", "fs" -> LiveTvCatchup.Kind.Flussonic
        "xc", "xtream" -> LiveTvCatchup.Kind.Xtream
        else -> if (template != null) LiveTvCatchup.Kind.Default else return null
    }
    if ((kind == LiveTvCatchup.Kind.Default || kind == LiveTvCatchup.Kind.Append) && template == null) return null
    return LiveTvCatchup(kind, (days ?: 1).coerceIn(1, 30), template)
}


private fun parseExtInf(line: String): M3uMetadata {
    // The display name follows the first comma outside quoted attributes; it may hold commas itself.
    val nameComma = firstUnquotedComma(line)
    val attributes = parseM3uAttributes(if (nameComma >= 0) line.substring(0, nameComma) else line)
    val displayName = (if (nameComma >= 0) line.substring(nameComma + 1) else "").trim()
        .ifBlank { attributes["tvg-name"].orEmpty() }
    return M3uMetadata(
        name = displayName,
        tvgId = attributes["tvg-id"]?.takeIf(String::isNotBlank),
        tvgName = attributes["tvg-name"]?.takeIf(String::isNotBlank),
        catchup = if (attributes.keys.any(CATCHUP_ATTRIBUTES::contains)) attributes.filterKeys(CATCHUP_ATTRIBUTES::contains) else emptyMap(),
        guideShiftMinutes = m3uGuideShiftMinutes(attributes["tvg-shift"]),
        logoUrl = attributes["tvg-logo"]?.takeIf(String::isNotBlank),
        group = attributes["group-title"].orEmpty(),
    )
}

/**
 * `key="value"` pairs (keys of letters, digits, `_` and `-`), read by hand: a playlist has one
 * #EXTINF line per channel, tens of thousands of them. Reads them as the pattern
 * `([\w-]+)="([^"]*)"` found them before, a later key replacing an earlier one.
 */
internal fun parseM3uAttributes(line: String): Map<String, String> {
    if ('"' !in line) return emptyMap()
    val attributes = LinkedHashMap<String, String>()
    var i = 0
    while (i < line.length) {
        if (!line[i].isM3uKeyChar()) {
            i++
            continue
        }
        var end = i
        while (end < line.length && line[end].isM3uKeyChar()) end++
        if (end + 1 < line.length && line[end] == '=' && line[end + 1] == '"') {
            val close = line.indexOf('"', end + 2)
            if (close < 0) break
            attributes[line.substring(i, end).lowercase()] = line.substring(end + 2, close).trim()
            i = close + 1
        } else {
            i = end
        }
    }
    return attributes
}

private fun Char.isM3uKeyChar(): Boolean =
    this in 'a'..'z' || this in 'A'..'Z' || this in '0'..'9' || this == '_' || this == '-'

/** Kodi style `url|User-Agent=...&Referer=...`. */
private fun parseUrlHeaders(line: String): Map<String, String> {
    val options = line.substringAfter('|', "")
    if (options.isEmpty()) return emptyMap()
    return options.split('&').mapNotNull { entry ->
        val key = entry.substringBefore('=').trim()
        val raw = entry.substringAfter('=', "").trim()
        // Decoded, then printable ASCII only: a header value with anything else fails the request.
        val value = (if ('%' in raw) runCatching { java.net.URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw) else raw)
            .filter { it in ' '..'~' }.trim()
        val validKey = key.isNotEmpty() && key.all { it.isLetterOrDigit() || it == '-' || it == '_' }
        if (!validKey || value.isBlank()) null else key to value
    }.toMap()
}

private fun parseExtHttpHeaders(value: String): Map<String, String> =
    // Usually JSON ({"User-Agent":"Mozilla/5.0 (KHTML, like Gecko)"}), whose values may hold commas.
    runCatching {
        val json = org.json.JSONObject(value.trim())
        json.keys().asSequence().mapNotNull { key ->
            json.optString(key).trim().takeIf { key.isNotBlank() && it.isNotBlank() }?.let { key to it }
        }.toMap()
    }.getOrNull() ?: value.trim().removePrefix("{").removeSuffix("}")
        .split(',')
        .mapNotNull { entry ->
            val key = entry.substringBefore(':').trim().trim('"')
            val headerValue = entry.substringAfter(':', "").trim().trim('"')
            if (key.isBlank() || headerValue.isBlank()) null else key to headerValue
        }
        .toMap()

/**
 * The guide of an Xtream panel's M3U link (`…/get.php?username=…&password=…`), as IPTV players
 * use it when the playlist names no guide: the panel serves it at `xmltv.php` with the same login.
 */
internal fun xtreamGuideUrlFor(playlistUrl: String): String? {
    val url = playlistUrl.toHttpUrlOrNull() ?: return null
    if (!url.encodedPath.endsWith("/get.php", ignoreCase = true)) return null
    val username = url.queryParameter("username")?.takeIf(String::isNotBlank) ?: return null
    val password = url.queryParameter("password")?.takeIf(String::isNotBlank) ?: return null
    val folder = url.encodedPath.dropLast("get.php".length)
    return url.newBuilder()
        .encodedPath(folder + "xmltv.php")
        .query(null)
        .addQueryParameter("username", username)
        .addQueryParameter("password", password)
        .build()
        .toString()
}

internal fun defaultStreamHeaders(url: String): Map<String, String> =
    if (url.isHttpUrl()) LIVE_TV_STREAM_HEADERS else emptyMap()

internal fun String.isHttpUrl(): Boolean =
    startsWith("http://", ignoreCase = true) || startsWith("https://", ignoreCase = true)

private val categoryHeadingRegex = Regex("""^\s*#+\s*.+\s*#+\s*$""")

/** "##### SPORTS #####" style separator entries some providers put in their lists. */
internal fun isLikelyCategoryHeading(name: String): Boolean {
    val trimmed = name.trim()
    // Cheap checks first: this runs for every channel of a list.
    return trimmed.length >= 3 && trimmed.startsWith('#') && trimmed.endsWith('#') &&
        categoryHeadingRegex.matches(trimmed)
}

/** A single stream link rather than a playlist (the user pasted one channel). */
internal fun String.looksLikeDirectVideoUrl(): Boolean {
    val path = substringBefore('#').substringBefore('?').lowercase()
    if (path.endsWith(".m3u") || path.endsWith(".m3u8")) return false
    return DIRECT_VIDEO_EXTENSIONS.any(path::endsWith)
}

internal fun directStreamChannel(url: String): LiveTvChannel =
    LiveTvChannel(
        id = "direct-${url.hashCode()}",
        name = url.substringBefore('?').substringAfterLast('/').ifBlank { "Live stream" },
        streamUrl = url,
        headers = defaultStreamHeaders(url),
    )

private val DIRECT_VIDEO_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".mov", ".avi", ".ts", ".mpeg", ".mpg")

internal val LIVE_TV_PLAYLIST_HEADERS = mapOf(
    "User-Agent" to "VLC/3.0.0 LibVLC/3.0.0",
    "Accept" to "application/x-mpegURL, application/vnd.apple.mpegurl, audio/mpegurl, text/plain, */*",
)

internal val LIVE_TV_STREAM_HEADERS = mapOf("User-Agent" to "VLC/3.0.0 LibVLC/3.0.0")

/** [headers] with the user agent a source was given ([LiveTvSource.userAgent]); as they are when it has none. */
internal fun withLiveTvUserAgent(headers: Map<String, String>, userAgent: String): Map<String, String> {
    // HTTP headers take printable ASCII only; a typed "é" or curly quote would fail every request.
    val agent = userAgent.filter { it in ' '..'~' }.trim()
    if (agent.isEmpty()) return headers
    return headers.filterKeys { !it.equals("User-Agent", ignoreCase = true) } + ("User-Agent" to agent)
}

private fun firstUnquotedComma(line: String): Int {
    var quoted = false
    for (i in line.indices) {
        when (line[i]) {
            '"' -> quoted = !quoted
            ',' -> if (!quoted) return i
        }
    }
    return -1
}
