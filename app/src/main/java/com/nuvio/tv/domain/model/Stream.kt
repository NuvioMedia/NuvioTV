package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Represents a stream source from a Stremio addon
 */
@Immutable
data class Stream(
    val name: String?,
    val title: String?,
    val description: String?,
    val url: String?,
    val ytId: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val externalUrl: String?,
    val behaviorHints: StreamBehaviorHints?,
    val addonName: String,
    val addonLogo: String?,
    val sources: List<String>? = null,
    val quality: String? = null,
    val qualityValue: Int = -1
) {
    /**
     * Returns the primary stream source URL. Prefers [url], then the first
     * playable entry from [sources] (addons that only fill [sources] are
     * still internally playable), then [externalUrl] as a last resort.
     */
    fun getStreamUrl(): String? = url ?: firstSourceUrl() ?: externalUrl

    /**
     * Returns all distinct, non-blank, whitespace-trimmed playable URLs
     * for this stream in preference order: [url] first, then [sources].
     */
    fun getStreamUrls(): List<String> {
        val result = LinkedHashSet<String>()
        url?.trim()?.takeIf { it.isNotEmpty() }?.let(result::add)
        sources?.forEach { raw ->
            raw.trim().takeIf { it.isNotEmpty() }?.let(result::add)
        }
        return result.toList()
    }

    /**
     * Returns true if this is a torrent-only stream (no HTTP URL available).
     * When both infoHash and url are present (e.g. debrid cached torrents),
     * the HTTP url is preferred and this returns false.
     */
    fun isTorrent(): Boolean = infoHash != null && url.isNullOrBlank()

    /**
     * Returns true if this is a YouTube stream
     */
    fun isYouTube(): Boolean = ytId != null

    /**
     * Returns true if the only playable location is an external URL that
     * must open in a browser. Streams with [url] or playable [sources] are
     * internally playable even if [externalUrl] is also set.
     */
    fun isExternal(): Boolean =
        externalUrl != null && url.isNullOrBlank() && firstSourceUrl() == null

    private fun firstSourceUrl(): String? =
        sources?.firstOrNull { !it.isNullOrBlank() }?.trim()

    /**
     * Returns a display name for the stream
     */
    fun getDisplayName(): String = name ?: title ?: description ?: "Unknown Stream"

    /**
     * Returns a display description for the stream
     */
    fun getDisplayDescription(): String? = description ?: title

    /**
     * Returns a stable key for use in LazyColumn/LazyRow.
     * Incorporates all content-identifying fields so the key doesn't change
     * when the list recomposes or items shift position. The [occurrence] parameter
     * disambiguates genuine duplicates (same addon+url+name+title).
     */
    fun stableKey(occurrence: Int = 0): String = buildString {
        append(addonName)
        append('\u0000')
        append(url ?: infoHash ?: ytId ?: externalUrl ?: "")
        append('\u0000')
        append(name ?: "")
        append('\u0000')
        append(title ?: "")
        if (occurrence > 0) {
            append('\u0000')
            append(occurrence)
        }
    }
}

@Immutable
data class StreamBehaviorHints(
    val notWebReady: Boolean?,
    val bingeGroup: String?,
    val countryWhitelist: List<String>?,
    val proxyHeaders: ProxyHeaders?,
    val videoHash: String? = null,
    val videoSize: Long? = null,
    val filename: String? = null
)

@Immutable
data class ProxyHeaders(
    val request: Map<String, String>?,
    val response: Map<String, String>?
)

/**
 * Represents streams grouped by addon source
 */
@Immutable
data class AddonStreams(
    val addonName: String,
    val addonLogo: String?,
    val streams: List<Stream>
)
