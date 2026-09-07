package com.nuvio.tv.core.iptv

import com.nuvio.tv.domain.model.IptvChannel
import java.util.Locale

/**
 * Parses an M3U/M3U8 extended playlist (#EXTM3U / #EXTINF format) into a flat
 * list of channels. Only the attributes commonly used by IPTV providers are
 * read - tvg-id/tvg-name/tvg-logo/group-title, plus any #EXTVLCOPT header
 * hints some providers require (user-agent/referrer) for the stream URL that
 * follows.
 */
object M3uParser {
    private val attributeRegex = Regex("""([\w-]+)="([^"]*)"""")

    fun parse(content: String): List<IptvChannel> {
        val lines = content.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val channels = mutableListOf<IptvChannel>()

        var pendingAttributes: Map<String, String>? = null
        var pendingDisplayName: String? = null
        var pendingHeaders: MutableMap<String, String>? = null

        for (line in lines) {
            when {
                line.startsWith("#EXTM3U", ignoreCase = true) -> continue
                line.startsWith("#EXTINF:", ignoreCase = true) -> {
                    val commaIndex = line.lastIndexOf(',')
                    val attributesPart = if (commaIndex >= 0) line.substring(0, commaIndex) else line
                    pendingDisplayName = if (commaIndex >= 0) line.substring(commaIndex + 1).trim() else null
                    pendingAttributes = attributeRegex.findAll(attributesPart)
                        .associate { it.groupValues[1].lowercase(Locale.ROOT) to it.groupValues[2] }
                    pendingHeaders = null
                }
                line.startsWith("#EXTVLCOPT:", ignoreCase = true) -> {
                    val opt = line.removePrefix("#EXTVLCOPT:").trim()
                    val eq = opt.indexOf('=')
                    if (eq > 0) {
                        val key = opt.substring(0, eq).trim().lowercase(Locale.ROOT)
                        val value = opt.substring(eq + 1).trim()
                        val headerName = when (key) {
                            "http-user-agent" -> "User-Agent"
                            "http-referrer" -> "Referer"
                            else -> null
                        }
                        if (headerName != null) {
                            if (pendingHeaders == null) pendingHeaders = mutableMapOf()
                            pendingHeaders!![headerName] = value
                        }
                    }
                }
                line.startsWith("#") -> continue
                else -> {
                    val attrs = pendingAttributes
                    if (attrs != null) {
                        val name = pendingDisplayName?.takeIf { it.isNotBlank() }
                            ?: attrs["tvg-name"]?.takeIf { it.isNotBlank() }
                            ?: "Unknown"
                        channels.add(
                            IptvChannel(
                                id = attrs["tvg-id"]?.takeIf { it.isNotBlank() } ?: line,
                                name = name,
                                logoUrl = attrs["tvg-logo"]?.takeIf { it.isNotBlank() },
                                groupTitle = attrs["group-title"]?.takeIf { it.isNotBlank() } ?: "Altro",
                                streamUrl = line,
                                epgId = attrs["tvg-id"]?.takeIf { it.isNotBlank() },
                                headers = pendingHeaders?.toMap()
                            )
                        )
                    }
                    pendingAttributes = null
                    pendingDisplayName = null
                    pendingHeaders = null
                }
            }
        }
        return channels
    }
}
