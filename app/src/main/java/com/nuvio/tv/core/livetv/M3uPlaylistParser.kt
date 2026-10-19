package com.nuvio.tv.core.livetv

import com.nuvio.tv.domain.model.LiveTvChannel
import com.nuvio.tv.domain.model.LiveTvImportResult
import java.net.URI
import java.security.MessageDigest

class LiveTvPlaylistFormatException(message: String) : IllegalArgumentException(message)

/** Parses user-provided extended M3U text without fetching URLs or logging playlist contents. */
object M3uPlaylistParser {
    const val MAX_PLAYLIST_CHARS = 8 * 1024 * 1024
    const val MAX_CHANNELS = 5_000
    private const val MAX_URL_CHARS = 8_192
    private const val MAX_NAME_CHARS = 200
    private const val MAX_GROUP_CHARS = 120
    private const val MAX_LOGO_URL_CHARS = 2_048

    private val attributePattern = Regex("""([\w-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^,\s]*))""")

    fun parse(text: String, sourceId: String): LiveTvImportResult {
        requireSourceId(sourceId)
        if (text.length > MAX_PLAYLIST_CHARS) {
            throw LiveTvPlaylistFormatException("Playlist exceeds the supported size")
        }
        val lines = text.removePrefix("\uFEFF").lineSequence().map(String::trim)
        if (lines.firstOrNull()?.equals("#EXTM3U", ignoreCase = true) != true) {
            throw LiveTvPlaylistFormatException("Playlist must start with #EXTM3U")
        }

        val channels = ArrayList<LiveTvChannel>()
        val seenIds = HashSet<String>()
        var pending: PendingEntry? = null
        var skipped = 0

        for (line in lines.drop(1)) {
            if (line.isEmpty()) continue
            if (line.startsWith("#EXTINF:", ignoreCase = true)) {
                if (pending != null) skipped++
                if (channels.size >= MAX_CHANNELS) {
                    throw LiveTvPlaylistFormatException("Playlist exceeds the channel limit")
                }
                pending = parseEntry(line.substringAfter(':'))
                continue
            }
            if (line.startsWith('#')) continue

            val entry = pending ?: continue
            pending = null
            val streamUrl = line.takeIf { it.length <= MAX_URL_CHARS && isHttpUrl(it) }
            val name = (entry.title.ifBlank { entry.attributes["tvg-name"].orEmpty() })
                .clean(MAX_NAME_CHARS)
            if (streamUrl == null || name.isBlank()) {
                skipped++
                continue
            }
            val id = "$sourceId:${sha256(streamUrl)}"
            if (!seenIds.add(id)) {
                skipped++
                continue
            }
            channels += LiveTvChannel(
                id = id,
                sourceId = sourceId,
                displayName = name,
                streamUrl = streamUrl,
                logoUrl = entry.attributes["tvg-logo"]
                    ?.takeIf { it.length <= MAX_LOGO_URL_CHARS && isHttpUrl(it) }
                    ?.clean(MAX_LOGO_URL_CHARS)
                    ?.ifBlank { null },
                groupName = entry.attributes["group-title"]?.clean(MAX_GROUP_CHARS)?.ifBlank { null },
                tvgId = entry.attributes["tvg-id"]?.clean(160)?.ifBlank { null },
                tvgName = entry.attributes["tvg-name"]?.clean(MAX_NAME_CHARS)?.ifBlank { null },
            )
        }
        if (pending != null) skipped++
        return LiveTvImportResult(channels = channels, skippedEntries = skipped)
    }

    private fun parseEntry(value: String): PendingEntry {
        val comma = value.indexOf(',')
        val metadata = if (comma >= 0) value.substring(0, comma) else value
        val title = if (comma >= 0) value.substring(comma + 1).trim() else ""
        val attributes = attributePattern.findAll(metadata).associate { match ->
            val key = match.groupValues[1].lowercase()
            val item = (2..4).firstNotNullOfOrNull { index ->
                match.groupValues[index].takeIf(String::isNotEmpty)
            }.orEmpty()
            key to item
        }
        return PendingEntry(title = title, attributes = attributes)
    }

    private fun isHttpUrl(value: String): Boolean = runCatching {
        val uri = URI(value)
        (uri.scheme.equals("http", true) || uri.scheme.equals("https", true)) &&
            !uri.host.isNullOrBlank() && uri.userInfo == null
    }.getOrDefault(false)

    private fun requireSourceId(value: String) {
        require(value.isNotBlank() && value.length <= 120 && value.none(Char::isISOControl)) {
            "Source id is invalid"
        }
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .take(12)
        .joinToString("") { byte -> "%02x".format(byte) }

    private fun String.clean(maxChars: Int): String =
        filterNot(Char::isISOControl).trim().take(maxChars)

    private data class PendingEntry(val title: String, val attributes: Map<String, String>)
}
