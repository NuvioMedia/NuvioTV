package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.nuvio.tv.core.debrid.DebridProviders

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
    val qualityValue: Int = -1,
    val clientResolve: StreamClientResolve? = null,
    val debridCacheStatus: StreamDebridCacheStatus? = null,
    val badges: List<StreamBadge> = emptyList()
) {
    /**
     * Returns the primary stream source URL
     */
    fun getStreamUrl(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { !it.isTorrentSourceLink() }

    /**
     * Returns a `magnet:` URI when one is present. Intentionally magnet-only:
     * debrid callers pass the value straight to provider APIs that reject
     * other schemes; `torrent://` streams resolve through [infoHash] instead.
     */
    fun torrentMagnetUri(): String? =
        listOfNotNull(url, externalUrl)
            .firstOrNull { it.isMagnetLink() }

    /**
     * Returns true if this is a torrent-only stream (no HTTP URL available).
     * When both infoHash and url are present (e.g. debrid cached torrents),
     * the HTTP url is preferred and this returns false.
     */
    fun isTorrent(): Boolean =
        !isDirectDebrid() &&
            getStreamUrl().isNullOrBlank() &&
            (!infoHash.isNullOrBlank() || !torrentMagnetUri().isNullOrBlank())

    fun needsLocalDebridResolve(): Boolean =
        isTorrent() && getStreamUrl().isNullOrBlank()

    fun isDirectDebrid(): Boolean {
        val resolve = clientResolve ?: return false
        return resolve.type.equals("debrid", ignoreCase = true) &&
            DebridProviders.isSupported(resolve.service) &&
            resolve.isCached == true
    }

    /**
     * Returns true if this is a YouTube stream
     */
    fun isYouTube(): Boolean = ytId != null

    /**
     * Returns true if this is an external URL (opens in browser)
     */
    fun isExternal(): Boolean = externalUrl != null && url == null && !externalUrl.isTorrentSourceLink()

    /**
     * Returns a display name for the stream, or null when no field is usable.
     * UI call sites should substitute a localized fallback (R.string.stream_unknown).
     */
    fun getDisplayNameOrNull(): String? = name ?: title ?: description

    /**
     * Returns a display name for the stream
     */
    fun getDisplayName(): String = getDisplayNameOrNull() ?: "Unknown Stream"

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
        append(url ?: infoHash ?: clientResolve?.infoHash ?: ytId ?: externalUrl ?: "")
        append('\u0000')
        append(clientResolve?.fileIdx ?: "")
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
data class StreamBadge(
    val name: String,
    val imageURL: String = "",
    val tagColor: String = "",
    val tagStyle: String = "",
    val textColor: String = "",
    val borderColor: String = ""
)

@Immutable
data class StreamDebridCacheStatus(
    val providerId: String,
    val providerName: String,
    val state: StreamDebridCacheState,
    val cachedName: String? = null,
    val cachedSize: Long? = null
)

enum class StreamDebridCacheState {
    CHECKING,
    CACHED,
    NOT_CACHED,
    UNKNOWN
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
data class StreamClientResolve(
    val type: String?,
    val infoHash: String?,
    val fileIdx: Int?,
    val magnetUri: String?,
    val sources: List<String>?,
    val torrentName: String?,
    val filename: String?,
    val mediaType: String?,
    val mediaId: String?,
    val mediaOnlyId: String?,
    val title: String?,
    val season: Int?,
    val episode: Int?,
    val service: String?,
    val serviceIndex: Int?,
    val serviceExtension: String?,
    val isCached: Boolean?,
    val stream: StreamClientResolveStream? = null
)

@Immutable
data class StreamClientResolveStream(
    val raw: StreamClientResolveRaw?
)

@Immutable
data class StreamClientResolveRaw(
    val torrentName: String?,
    val filename: String?,
    val size: Long?,
    val folderSize: Long?,
    val tracker: String?,
    val indexer: String?,
    val network: String?,
    val parsed: StreamClientResolveParsed?
)

@Immutable
data class StreamClientResolveParsed(
    val rawTitle: String?,
    val parsedTitle: String?,
    val year: Int?,
    val resolution: String?,
    val seasons: List<Int>?,
    val episodes: List<Int>?,
    val quality: String?,
    val hdr: List<String>?,
    val codec: String?,
    val audio: List<String>?,
    val channels: List<String>?,
    val languages: List<String>?,
    val group: String?,
    val network: String?,
    val edition: String?,
    val duration: Long?,
    val bitDepth: String?,
    val extended: Boolean?,
    val theatrical: Boolean?,
    val remastered: Boolean?,
    val unrated: Boolean?
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

private fun String?.isMagnetLink(): Boolean =
    this?.trimStart()?.startsWith("magnet:", ignoreCase = true) == true

private fun String?.isTorrentSchemeLink(): Boolean =
    this?.trimStart()?.startsWith("torrent://", ignoreCase = true) == true

/**
 * True for any URL that identifies a torrent source rather than a playable HTTP
 * stream, i.e. `magnet:` and `torrent://` links. Such URLs must never be handed
 * to ExoPlayer; they go through TorrServer instead.
 */
internal fun String?.isTorrentSourceLink(): Boolean =
    isMagnetLink() || isTorrentSchemeLink()

private val BTIH_HASH_REGEX = Regex("[0-9a-fA-F]{40}|[2-7A-Za-z]{32}")
private val MAGNET_BTIH_PARAM_REGEX = Regex("xt=urn:btih:([^&#]+)", RegexOption.IGNORE_CASE)
private const val TORRENT_SCHEME_PREFIX = "torrent://"

/**
 * Extracts a BitTorrent info hash from a `magnet:` or `torrent://` URL.
 * Parses each format explicitly — the `xt=urn:btih:` parameter for magnets and
 * the authority segment for `torrent://` — and validates the candidate is a
 * full hex (40 char) or base32 (32 char) hash, so hash-like sequences in other
 * query parameters or path segments are never misattributed. Returns null when
 * the URL is not a torrent source link or carries no valid hash.
 */
internal fun extractInfoHashFromTorrentLink(rawUrl: String?): String? {
    val trimmed = rawUrl?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val candidate = when {
        trimmed.isMagnetLink() ->
            MAGNET_BTIH_PARAM_REGEX.find(trimmed)?.groupValues?.get(1)
        trimmed.isTorrentSchemeLink() ->
            trimmed.substring(TORRENT_SCHEME_PREFIX.length)
                .takeWhile { it != '/' && it != '?' && it != '#' }
        else -> null
    }?.takeIf { it.isNotBlank() } ?: return null
    return candidate.takeIf { BTIH_HASH_REGEX.matches(it) }
}
