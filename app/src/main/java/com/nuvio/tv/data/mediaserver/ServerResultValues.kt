package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.usenet.UsenetRelease
import com.nuvio.tv.core.usenet.UsenetResultValues
import java.util.Locale

/**
 * The fields a media server's version gives the stream formatter, under the names and labels a debrid stream uses, so
 * the same templates show both. The file name is read like a release name, together with the server's own words for
 * the streams; the resolution, codec and bitrate the server measured take over from the name's.
 */
internal object ServerResultValues {
    /** [service] is the kind of server (Plex, Jellyfin…), [server] its name and [source] the group its results show under. */
    fun of(candidate: ServerCandidate, service: String, server: String, source: String,
        languageEmoji: (String) -> String): Map<String, Any?> {
        val media = candidate.media
        val name = candidate.filename?.takeIf { it.isNotBlank() } ?: candidate.title
        val values = UsenetResultValues.of(UsenetRelease(name, nzbUrl = "", size = candidate.sizeBytes ?: 0),
            episodeSize = null, failed = false, languageEmoji = languageEmoji, labels = media.labels.joinToString(" "))
        val measured = listOfNotNull(
            media.resolution?.let { "stream.resolution" to "${it}p" },
            media.videoCodec?.let { "stream.encode" to it }
        )
        return values + measured + mapOf(
            "stream.bitrate" to media.bitrateKbps?.takeIf { it > 0 }?.let(::bitrate),
            "stream.filename" to candidate.filename,
            "stream.type" to "server",
            // A server's file plays at once.
            "service.cached" to true,
            "service.shortName" to service,
            "service.name" to server,
            "addon.name" to source
        )
    }

    /** As a built-in Usenet result shows it: "72 Mbps", "8.4 Mbps". */
    private fun bitrate(kbps: Long): String {
        val mbps = kbps / 1000.0
        return (if (mbps < 10) "%.1f Mbps" else "%.0f Mbps").format(Locale.ROOT, mbps)
    }
}
