package com.nuvio.tv.reshaped.livetv

import android.util.Log
import android.util.JsonReader
import android.util.JsonToken
import java.io.InputStream
import java.io.InputStreamReader
import java.net.URLEncoder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import org.json.JSONObject

/*
 * Xtream Codes and Stalker (MAG) providers. Channel lists are read with a streaming JSON reader,
 * one entry at a time, so a 20 000 channel provider never becomes a JSON tree in memory.
 */

internal fun String.urlEncoded(): String = URLEncoder.encode(this, "UTF-8").replace("+", "%20")

private fun String.isHttp(): Boolean = isHttpUrl()

// region Xtream

internal fun LiveTvXtreamSettings.normalized(): LiveTvXtreamSettings = copy(
    serverUrl = serverUrl.trim().trimEnd('/').substringBefore("/player_api.php").trimEnd('/'),
    username = username.trim(),
    password = password.trim(),
)

/** A provider's channels and its categories in the provider's own order. */
internal class ProviderChannels(val channels: List<LiveTvChannel>, val groupOrder: List<String>, val incomplete: Boolean = false)

internal object LiveTvXtream {
    /** Each panel's time zone (by server URL): catch-up start times are given in it. */
    private val zones = java.util.concurrent.ConcurrentHashMap<String, java.time.ZoneId>()

    /** [userAgent]: the source's own ([LiveTvSource.userAgent]); blank for the default. */
    suspend fun channels(settings: LiveTvXtreamSettings, userAgent: String = ""): ProviderChannels {
        val apiHeaders = withLiveTvUserAgent(LIVE_TV_PLAYLIST_HEADERS, userAgent)
        val streamHeaders = withLiveTvUserAgent(LIVE_TV_STREAM_HEADERS, userAgent)
        // A busy panel that fails the categories still lists its channels (Uncategorised).
        val categories = try {
            LiveTvHttp.stream(apiUrl(settings, "get_live_categories"), apiHeaders) { input ->
                readObjects(input) { fields ->
                    val id = fields["category_id"] ?: fields["id"] ?: return@readObjects null
                    val name = fields["category_name"] ?: fields["name"] ?: return@readObjects null
                    id to name
                }
            }.toMap()
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("LiveTvXtream", "Could not read the categories", error)
            emptyMap()
        }
        val extension = liveExtension(settings, apiHeaders)
        // One catch-up instance per archive length, shared by the channels that have it.
        val catchups = HashMap<Int, LiveTvCatchup>()
        val seen = HashSet<String>()
        // Big panels build the whole list before the first byte, as get.php does: the same long wait.
        val channels = LiveTvHttp.stream(apiUrl(settings, "get_live_streams"), apiHeaders, LiveTvHttp.LIST_READ_TIMEOUT_S) { input ->
            var index = 0
            readObjects(input) { fields ->
                val position = index++
                val name = fields["name"] ?: return@readObjects null
                val streamId = fields["stream_id"] ?: fields["id"] ?: return@readObjects null
                // Always the panel's own link, as IPTV players use: "direct_source" is often the
                // panel's upstream origin, which refuses clients, and the panel redirects to it when it is meant to be used.
                val streamUrl = settings.liveStreamUrl(streamId, extension)
                if (!seen.add(streamUrl)) return@readObjects null
                LiveTvChannel(
                    id = "xtream-$streamId-$position",
                    name = name,
                    streamUrl = streamUrl,
                    tvgId = fields["epg_channel_id"] ?: fields["tvg_id"],
                    logoUrl = fields["stream_icon"] ?: fields["logo"],
                    group = fields["category_id"]?.let(categories::get).orEmpty(),
                    headers = streamHeaders,
                    // Panels write it as 1, "1", true or a count of days.
                    catchup = if (fields["tv_archive"].let { it == "true" || (it?.toDoubleOrNull() ?: 0.0) > 0.0 }) {
                        val days = fields["tv_archive_duration"]?.toIntOrNull()?.coerceIn(1, 30) ?: 1
                        catchups.getOrPut(days) { LiveTvCatchup(LiveTvCatchup.Kind.Xtream, days) }
                    } else {
                        null
                    },
                )
            }
        }
        return ProviderChannels(channels, categories.values.map(String::trim).distinct())
    }

    /**
     * The panel's time zone, which catch-up links give their start time in: from the login's
     * `server_info.timezone`, read with the channel list (or now, once, when it was not), else the TV's.
     */
    suspend fun zone(serverUrl: String, username: String, password: String, userAgent: String = ""): java.time.ZoneId {
        zones[serverUrl]?.let { return it }
        // A login that failed (panel busy, timeout) is asked again, at most once a minute.
        zoneFailedAt[serverUrl]?.let { if (System.currentTimeMillis() - it < 60_000L) return java.time.ZoneId.systemDefault() }
        val settings = LiveTvXtreamSettings(serverUrl, username, password)
        val login = runCatching { JSONObject(LiveTvHttp.text(loginUrl(settings), withLiveTvUserAgent(LIVE_TV_PLAYLIST_HEADERS, userAgent))) }
            .onFailure { if (it is CancellationException) throw it }
            .getOrNull()
        if (login == null) {
            zoneFailedAt[serverUrl] = System.currentTimeMillis()
            return java.time.ZoneId.systemDefault()
        }
        // A panel that gives no zone answers in the TV's own, as players assume.
        return (zoneOf(login) ?: java.time.ZoneId.systemDefault()).also { zones[serverUrl] = it }
    }

    private val zoneFailedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()

    private fun zoneOf(login: JSONObject): java.time.ZoneId? =
        login.optJSONObject("server_info")?.optString("timezone")?.trim()?.takeIf(String::isNotEmpty)
            ?.let { runCatching { java.time.ZoneId.of(it) }.getOrNull() }

    /**
     * The live format this account may use: MPEG-TS, as IPTV players prefer, unless the account only
     * allows HLS ("allowed_output_formats" in the login reply); a TS link then fails on every channel.
     */
    private suspend fun liveExtension(settings: LiveTvXtreamSettings, headers: Map<String, String>): String {
        val formats = try {
            val login = JSONObject(LiveTvHttp.text(loginUrl(settings), headers))
            zoneOf(login)?.let { zones[settings.serverUrl] = it }
            val allowed = login.optJSONObject("user_info")?.optJSONArray("allowed_output_formats")
            if (allowed == null) emptyList() else List(allowed.length()) { allowed.optString(it).trim().lowercase() }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            Log.w("LiveTvXtream", "Could not read the account's formats; using TS", error)
            emptyList()
        }
        return if (formats.isEmpty() || "ts" in formats || "m3u8" !in formats) "ts" else "m3u8"
    }

    /**
     * The programmes of stream [streamId] that ended after [sinceMs], from the panel's own listing
     * (get_simple_data_table): it covers the channel's whole catch-up even when the panel's guide
     * file lists only a day back. Oldest first; titles are base64, as panels send them.
     */
    suspend fun pastProgrammes(serverUrl: String, username: String, password: String, streamId: String, sinceMs: Long, userAgent: String = ""): List<LiveTvProgramme> {
        val settings = LiveTvXtreamSettings(serverUrl, username, password)
        val url = apiUrl(settings, "get_simple_data_table") + "&stream_id=${streamId.urlEncoded()}"
        val nowMs = LiveTvClock.nowEpochMs()
        val titles = HashMap<String, String>()
        return LiveTvHttp.stream(url, withLiveTvUserAgent(LIVE_TV_PLAYLIST_HEADERS, userAgent)) { input ->
            readObjects(input, arrayName = "epg_listings") { fields ->
                val start = fields["start_timestamp"]?.toLongOrNull()?.times(1000L) ?: return@readObjects null
                val stop = fields["stop_timestamp"]?.toLongOrNull()?.times(1000L) ?: return@readObjects null
                if (stop <= start || stop <= sinceMs || stop > nowMs) return@readObjects null
                val title = fields["title"]?.let(::decodeBase64)?.trim()?.takeIf(String::isNotEmpty) ?: return@readObjects null
                LiveTvProgramme(title = titles.getOrPut(title) { title }, startEpochMs = start, stopEpochMs = stop)
            }
        }.sortedBy { it.startEpochMs }
    }

    /** Panels send titles base64 encoded; one that is not (some do send plain text) is kept as it is. */
    private fun decodeBase64(value: String): String =
        runCatching { String(android.util.Base64.decode(value, android.util.Base64.DEFAULT), Charsets.UTF_8) }
            .getOrNull()?.takeIf { decoded -> decoded.none { it == '\uFFFD' || (it < ' ' && it != '\n' && it != '\t') } }
            ?: value

    private fun loginUrl(settings: LiveTvXtreamSettings): String =
        "${settings.serverUrl}/player_api.php?username=${settings.username.urlEncoded()}" +
            "&password=${settings.password.urlEncoded()}"

    private fun apiUrl(settings: LiveTvXtreamSettings, action: String): String =
        "${settings.serverUrl}/player_api.php?username=${settings.username.urlEncoded()}" +
            "&password=${settings.password.urlEncoded()}&action=${action.urlEncoded()}"

    private fun LiveTvXtreamSettings.liveStreamUrl(streamId: String, extension: String): String =
        "$serverUrl/live/${username.urlEncoded()}/${password.urlEncoded()}/${streamId.urlEncoded()}.$extension"
}

// endregion

// region Stalker

internal fun LiveTvStalkerSettings.normalized(): LiveTvStalkerSettings = copy(
    portalUrl = portalUrl.trim().trimEnd('/'),
    macAddress = macAddress.trim().uppercase().let { mac ->
        // "001A79ABCDEF" or "00-1A-79-…" as the portal knows it: 00:1A:79:AB:CD:EF.
        val hex = mac.filter { it in '0'..'9' || it in 'A'..'F' }
        if (hex.length == 12 && mac.none { it in 'G'..'Z' }) hex.chunked(2).joinToString(":") else mac
    },
    username = username.trim(),
    password = password.trim(),
)

internal data class StalkerChannels(val channels: List<LiveTvChannel>, val incomplete: Boolean, val groupOrder: List<String> = emptyList())

private class StalkerSession(val settings: LiveTvStalkerSettings, val token: String) {
    /** Built once per session and shared by every channel, not copied into each. */
    var playbackHeaders: Map<String, String>? = null
}

internal object LiveTvStalker {
    /** 14 channels a page on most portals: about 28,000 channels. */
    private const val MAX_PAGES = 2_000
    private const val PARALLEL_PAGES = 4

    /** One session per portal login, so several Stalker sources do not keep renewing each other's. */
    private val sessions = java.util.concurrent.ConcurrentHashMap<LiveTvStalkerSettings, StalkerSession>()

    fun clearSession() {
        sessions.clear()
    }

    /** The portal's channels; [StalkerChannels.incomplete] when some pages still failed after a retry. */
    suspend fun channels(settings: LiveTvStalkerSettings): StalkerChannels = withSession(
        settings,
        // An expired session lists nothing (or "Authorization failed"): ask once more with a new one.
        retryIf = { it.channels.isEmpty() },
    ) { session ->
        val genres = genres(session)
        var index = 0
        val toChannel: (Map<String, String>) -> LiveTvChannel? = { fields -> fields.toChannel(session, genres, index++) }
        // One request where the portal supports it, otherwise every page of the ordered list.
        val all = runCatching { dataObjects(session, "itv", "get_all_channels", toChannel) }
            .getOrElse { if (it is CancellationException) throw it else emptyList() }
        val seen = HashSet<String>()
        val result = if (all.isNotEmpty()) StalkerChannels(all, incomplete = false) else orderedPages(session, toChannel)
        result.copy(
            channels = ArrayList(result.channels.filter { seen.add(it.id.ifBlank { it.streamUrl }) }),
            groupOrder = genres.values.map(String::trim).distinct(),
        )
    }

    /**
     * The portal's own guide for the next [hours] hours, saved to [target] as a small gzipped
     * XMLTV file so it is read like any other guide. [channels] maps the portal's channel ids to
     * the ids the list keeps their guide under. Portals send it as one JSON answer, which is read
     * one programme at a time.
     */
    suspend fun downloadGuide(settings: LiveTvStalkerSettings, channels: Map<String, String>, hours: Int, target: java.io.File) {
        withSession(settings.normalized()) { session ->
            val programmes = LiveTvHttp.stream(
                url(session.settings, session.token, "itv", "get_epg_info", mapOf("period" to hours.toString())),
                baseHeaders(session.settings) + tokenHeader(session.token),
                LiveTvHttp.GUIDE_READ_TIMEOUT_S,
            ) { input ->
                var count = 0
                LiveTvHttp.writeGzip(target) { out ->
                    java.io.BufferedWriter(java.io.OutputStreamWriter(out, Charsets.UTF_8), 64 * 1024).let { writer ->
                        count = writeStalkerGuide(input, channels, writer)
                        writer.flush()
                    }
                    // An empty answer keeps the guide saved before.
                    count > 0
                }
                count
            }
            // An expired session answers with no programmes: withSession renews it and asks once more.
            if (programmes == 0) throw java.io.IOException("Portal sent no guide")
        }
    }

    /** A playable link for a list entry: Stalker links are created per play and expire. */
    suspend fun resolve(settings: LiveTvStalkerSettings, channel: LiveTvChannel): LiveTvChannel {
        val command = channel.stalkerCommand ?: return channel
        return withSession(settings.normalized()) { session ->
            val js = JSONObject(request(session.settings, session.token, "itv", "create_link", mapOf("cmd" to command)))
                .let { it.optJSONObject("js") ?: it }
            // An expired session answers without a link: the failure renews it once (withSession).
            val link = (js.optNonBlank("cmd") ?: js.optNonBlank("url") ?: js.optNonBlank("stream_url"))
                ?.toStalkerPlayableUrl()?.takeIf(String::isNotBlank)
                ?: throw IllegalStateException("no link")
            channel.copy(streamUrl = link, headers = channel.headers + playbackHeaders(session))
        }
    }

    /**
     * Runs [block] with a portal session. Portals expire sessions without notice, so a failure
     * with a cached session is retried once after a fresh handshake.
     */
    private suspend fun <T> withSession(
        settings: LiveTvStalkerSettings,
        retryIf: (T) -> Boolean = { false },
        block: suspend (StalkerSession) -> T,
    ): T {
        val cached = sessions[settings]
        if (cached != null) {
            try {
                val result = block(cached)
                if (!retryIf(result)) return result
                sessions.remove(settings, cached)
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (_: Exception) {
                sessions.remove(settings, cached)
            }
        }
        return block(handshake(settings))
    }

    private suspend fun handshake(settings: LiveTvStalkerSettings): StalkerSession {
        val token = settings.portalEndpoints().let { candidates ->
            var failure: Exception? = null
            // Each link is tried as given and kept only once it answers, so a parallel request
            // never borrows a link that is still being tried.
            candidates.firstNotNullOfOrNull { endpoint ->
                try {
                    JSONObject(request(settings, null, "stb", "handshake", endpoint = endpoint))
                        .let { it.optJSONObject("js") ?: it }
                        .optNonBlank("token")
                        ?.also { endpoints[settings] = endpoint }
                        // The portal answered but gave no token (MAC not allowed): that is the error to show.
                        ?: run { failure = LiveTvException(LiveTvError.StalkerToken); null }
                } catch (cancel: CancellationException) {
                    throw cancel
                } catch (error: Exception) {
                    if (failure !is LiveTvException) failure = error
                    null
                }
            } ?: run {
                throw failure ?: LiveTvException(LiveTvError.StalkerToken)
            }
        }
        // Many portals only list channels after the device profile was requested with the token.
        runCatching { request(settings, token, "stb", "get_profile") }
            .onFailure { if (it is CancellationException) throw it }
        return StalkerSession(settings, token).also { sessions[settings] = it }
    }

    private suspend fun genres(session: StalkerSession): Map<String, String> =
        dataObjects(session, "itv", "get_genres") { fields ->
            val id = fields["id"] ?: fields["alias"]
            val title = fields["title"] ?: fields["name"]
            if (id == null || title == null) null else id to title
        }.toMap()

    private suspend fun orderedPages(
        session: StalkerSession,
        toChannel: (Map<String, String>) -> LiveTvChannel?,
    ): StalkerChannels {
        // Pages load a few at a time, so the mapper is shared across threads.
        val mapper: (Map<String, String>) -> LiveTvChannel? = { synchronized(this) { toChannel(it) } }
        suspend fun page(number: Int): StalkerPage<LiveTvChannel> =
            LiveTvHttp.stream(
                url(session.settings, session.token, "itv", "get_ordered_list", mapOf("p" to number.toString())),
                baseHeaders(session.settings) + tokenHeader(session.token),
            ) { input -> readStalkerPage(input, mapper) }

        val first = page(1)
        if (first.entries.isEmpty()) return StalkerChannels(emptyList(), incomplete = false)
        var failedPages = 0
        // More pages than are read: the list is shown, but said to be incomplete.
        var capped = false
        val entries = ArrayList(first.entries)
        val perPage = first.maxPageItems?.takeIf { it > 0 } ?: first.entries.size
        val total = first.totalItems
        if (total != null && perPage > 0) {
            val pages = (total + perPage - 1) / perPage
            capped = pages > MAX_PAGES
            val lastPage = pages.coerceAtMost(MAX_PAGES)
            (2..lastPage).chunked(PARALLEL_PAGES).forEach { numbers ->
                coroutineScope {
                    numbers.map { number ->
                        async {
                            // A portal that drops a page under load usually serves it on a second try.
                            runCatching { page(number).entries }
                                .recoverCatching { if (it is CancellationException) throw it else page(number).entries }
                                .getOrElse {
                                    if (it is CancellationException) throw it
                                    synchronized(entries) { failedPages++ }
                                    emptyList()
                                }
                        }
                    }.awaitAll()
                }.forEach(entries::addAll)
            }
        } else {
            var number = 2
            while (true) {
                val data = page(number).entries
                if (data.isEmpty()) break
                entries += data
                if (number == MAX_PAGES) {
                    capped = runCatching { page(number + 1).entries.isNotEmpty() }
                        .onFailure { if (it is CancellationException) throw it }.getOrDefault(false)
                    break
                }
                number++
            }
        }
        if (failedPages > 0) Log.w("LiveTv", "Stalker: $failedPages pages could not be loaded")
        if (capped) Log.w("LiveTv", "Stalker: only the first $MAX_PAGES pages were read")
        return StalkerChannels(entries, incomplete = failedPages > 0 || capped)
    }

    private fun Map<String, String>.toChannel(
        session: StalkerSession,
        genres: Map<String, String>,
        index: Int,
    ): LiveTvChannel? {
        val name = this["name"] ?: this["title"] ?: return null
        val command = this["cmd"] ?: this["mc_cmd"] ?: this["url"] ?: return null
        val streamUrl = command.toStalkerPlayableUrl().takeIf(String::isNotBlank) ?: return null
        return LiveTvChannel(
            id = this["id"] ?: "stalker-$index-${streamUrl.hashCode()}",
            name = name,
            streamUrl = streamUrl,
            tvgId = this["xmltv_id"] ?: this["tvg_id"],
            logoUrl = (this["logo"] ?: this["logo_url"])?.let { session.settings.stalkerLogoUrl(it) },
            group = (this["tv_genre_id"] ?: this["genre_id"])?.let(genres::get).orEmpty(),
            headers = playbackHeaders(session),
            stalkerCommand = command,
        )
    }

    /** The `data` array of a `{js:{data:[...]}}` answer, each entry mapped from its plain fields. */
    private suspend fun <T : Any> dataObjects(
        session: StalkerSession,
        type: String,
        action: String,
        map: (Map<String, String>) -> T?,
    ): List<T> =
        LiveTvHttp.stream(
            url(session.settings, session.token, type, action),
            baseHeaders(session.settings) + tokenHeader(session.token),
        ) { input -> readStalkerPage(input, map).entries }

    private suspend fun request(
        settings: LiveTvStalkerSettings,
        token: String?,
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
        endpoint: String = settings.portalEndpoint(),
    ): String = LiveTvHttp.text(url(settings, token, type, action, extra, endpoint), baseHeaders(settings) + tokenHeader(token))

    private fun url(
        settings: LiveTvStalkerSettings,
        token: String?,
        type: String,
        action: String,
        extra: Map<String, String> = emptyMap(),
        endpoint: String = settings.portalEndpoint(),
    ): String {
        val parameters = buildMap {
            put("type", type)
            put("action", action)
            put("JsHttpRequest", "1-xml")
            if (!token.isNullOrBlank()) put("token", token)
            if (settings.username.isNotBlank()) put("login", settings.username)
            if (settings.password.isNotBlank()) put("password", settings.password)
            putAll(extra)
        }
        return endpoint + parameters.entries.joinToString("&", prefix = if ('?' in endpoint) "&" else "?") { (key, value) ->
            "${key.urlEncoded()}=${value.urlEncoded()}"
        }
    }

    private fun playbackHeaders(session: StalkerSession): Map<String, String> =
        session.playbackHeaders ?: (baseHeaders(session.settings) + tokenHeader(session.token)).also { session.playbackHeaders = it }

    private fun baseHeaders(settings: LiveTvStalkerSettings): Map<String, String> = mapOf(
        "User-Agent" to "Mozilla/5.0 (QtEmbedded; U; Linux; MAG254; en) AppleWebKit/533.3 (KHTML, like Gecko) MAG200 stbapp ver: 4 rev: 2721 Mobile Safari/533.3",
        "X-User-Agent" to "Model: MAG254; Link: Ethernet",
        "Referer" to settings.portalReferer(),
        "Cookie" to "mac=${settings.macAddress}; stb_lang=en; timezone=${java.util.TimeZone.getDefault().id.urlEncoded()}",
    )

    private fun tokenHeader(token: String?): Map<String, String> =
        if (token.isNullOrBlank()) emptyMap() else mapOf("Authorization" to "Bearer $token")

    /** The API link that answered the handshake, per portal (see [portalEndpoints]). */
    private val endpoints = java.util.concurrent.ConcurrentHashMap<LiveTvStalkerSettings, String>()

    private fun LiveTvStalkerSettings.portalEndpoint(): String = endpoints[this] ?: portalEndpoints().first()

    /**
     * Where the portal's API may be, most likely first: portal.php next to the client page, then
     * Ministra's …/stalker_portal/server/load.php. A link to either file is used as given.
     */
    private fun LiveTvStalkerSettings.portalEndpoints(): List<String> {
        val normalized = portalUrl.trim().trimEnd('/')
        val path = normalized.substringBefore('?')
        if (path.endsWith("portal.php", ignoreCase = true) || path.endsWith("load.php", ignoreCase = true)) return listOf(normalized)
        val portalPhp = if (path.endsWith("/c", ignoreCase = true)) path.dropLast(2) + "/portal.php" else "$path/portal.php"
        val ministra = path.indexOf("/stalker_portal", ignoreCase = true)
        val loadPhp = if (ministra >= 0) {
            path.substring(0, ministra + "/stalker_portal".length) + "/server/load.php"
        } else {
            (if (path.endsWith("/c", ignoreCase = true)) path.dropLast(2) else path) + "/server/load.php"
        }
        return listOf(portalPhp, loadPhp).distinct()
    }

    /** The portal's client page ("…/c/"), which real boxes send as their Referer. */
    private fun LiveTvStalkerSettings.portalReferer(): String {
        val path = portalUrl.trim().substringBefore('?').trimEnd('/')
        val ministra = path.indexOf("/stalker_portal", ignoreCase = true)
        val base = when {
            ministra >= 0 -> path.substring(0, ministra + "/stalker_portal".length)
            path.endsWith("/portal.php", ignoreCase = true) -> path.dropLast("/portal.php".length)
            path.endsWith("/load.php", ignoreCase = true) -> path.dropLast("/load.php".length)
            path.endsWith("/c", ignoreCase = true) -> path.dropLast(2)
            else -> path
        }
        return "$base/c/"
    }


    /**
     * A channel logo as a link. Many portals give only the file name ("1234.png"), served from
     * the portal's own logo folder.
     */
    private fun LiveTvStalkerSettings.stalkerLogoUrl(value: String): String? {
        val logo = value.trim()
        return when {
            logo.isEmpty() -> null
            logo.isHttp() -> logo
            logo.startsWith("//") -> "http:$logo"
            logo.startsWith("/") -> portalOrigin()?.let { it + logo }
            logo.contains("://") -> null
            else -> portalRoot()?.let { "$it/misc/logos/320/$logo" }
        }
    }

    /** `http://host:port` of the portal. */
    private fun LiveTvStalkerSettings.portalOrigin(): String? {
        val url = portalUrl.trim()
        val scheme = url.indexOf("://").takeIf { it > 0 } ?: return null
        val pathStart = url.indexOf('/', scheme + 3)
        return if (pathStart < 0) url.trimEnd('/') else url.substring(0, pathStart)
    }

    /** The portal's folder ("…/stalker_portal"), which holds its logos. */
    private fun LiveTvStalkerSettings.portalRoot(): String? {
        val url = portalUrl.trim().substringBefore('?')
        val marker = url.indexOf("/stalker_portal", ignoreCase = true)
        if (marker >= 0) return url.substring(0, marker + "/stalker_portal".length)
        return portalOrigin()?.let { "$it/stalker_portal" }
    }

    /** "ffmpeg http://…", "ffrt2 http://…", "auto  http://…": the link part. */
    private fun String.toStalkerPlayableUrl(): String {
        val parts = trim().split(' ', '\t').filter(String::isNotBlank)
        return (parts.firstOrNull { "://" in it } ?: parts.lastOrNull()).orEmpty()
    }

    private fun JSONObject.optNonBlank(name: String): String? =
        if (isNull(name)) null else optString(name).trim().takeIf(String::isNotBlank)
}

/**
 * Copies a Stalker `get_epg_info` answer (`{"js":{"data":{"<ch_id>":[{..},..]}}}`, or `data` as
 * one array of programmes with their `ch_id`) to [out] as XMLTV, keeping the channels in
 * [channels]. Returns how many programmes were written.
 */
private fun writeStalkerGuide(input: InputStream, channels: Map<String, String>, out: java.io.Writer): Int {
    var count = 0
    out.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<tv>\n")
    JsonReader(InputStreamReader(input.withoutBom(), Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        fun programme(channelId: String?) {
            var chId = channelId
            var title: String? = null
            var description: String? = null
            var start: Long? = null
            var stop: Long? = null
            var startText: String? = null
            var stopText: String? = null
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "ch_id" -> reader.nextScalar()?.let { if (chId == null) chId = it }
                    "name" -> title = reader.nextScalar()
                    "descr" -> description = reader.nextScalar()?.trim()?.takeIf(String::isNotEmpty)
                    "start_timestamp" -> start = reader.nextScalar()?.toLongOrNull()
                    "stop_timestamp" -> stop = reader.nextScalar()?.toLongOrNull()
                    "time" -> startText = reader.nextScalar()
                    "time_to" -> stopText = reader.nextScalar()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            val guideId = chId?.let(channels::get) ?: return
            // Timestamps are seconds; the text times are in the time zone the portal was sent (the TV's).
            val startSeconds = start ?: startText?.let(::stalkerLocalSeconds) ?: return
            val stopSeconds = stop ?: stopText?.let(::stalkerLocalSeconds) ?: return
            val name = title ?: return
            if (stopSeconds <= startSeconds) return
            out.write("<programme start=\"")
            out.write(xmlTvTime(startSeconds))
            out.write("\" stop=\"")
            out.write(xmlTvTime(stopSeconds))
            out.write("\" channel=\"")
            out.write(xmlEscaped(guideId))
            out.write("\"><title>")
            out.write(xmlEscaped(name))
            out.write("</title>")
            description?.let {
                out.write("<desc>")
                out.write(xmlEscaped(it))
                out.write("</desc>")
            }
            out.write("</programme>\n")
            count++
        }
        fun programmes(channelId: String?) {
            reader.beginArray()
            while (reader.hasNext()) {
                if (reader.peek() == JsonToken.BEGIN_OBJECT) programme(channelId) else reader.skipValue()
            }
            reader.endArray()
        }
        fun data() {
            when (reader.peek()) {
                JsonToken.BEGIN_OBJECT -> {
                    reader.beginObject()
                    while (reader.hasNext()) {
                        val channelId = reader.nextName()
                        if (channelId in channels && reader.peek() == JsonToken.BEGIN_ARRAY) programmes(channelId) else reader.skipValue()
                    }
                    reader.endObject()
                }
                JsonToken.BEGIN_ARRAY -> programmes(null)
                else -> reader.skipValue()
            }
        }
        fun body() {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "js" -> if (reader.peek() == JsonToken.BEGIN_OBJECT) body() else reader.skipValue()
                    "data" -> data()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        if (reader.peek() == JsonToken.BEGIN_OBJECT) body()
    }
    out.write("</tv>\n")
    return count
}

/** `2026-09-29 21:00:00` in the TV's time zone, as epoch seconds. */
private fun stalkerLocalSeconds(text: String): Long? = runCatching {
    java.time.LocalDateTime.parse(text.trim().replace(' ', 'T'))
        .atZone(java.time.ZoneId.systemDefault()).toEpochSecond()
}.getOrNull()

/** Epoch seconds as XMLTV `yyyyMMddHHmmss +0000`. */
private fun xmlTvTime(epochSeconds: Long): String {
    val time = java.time.LocalDateTime.ofEpochSecond(epochSeconds, 0, java.time.ZoneOffset.UTC)
    return buildString(20) {
        append(time.year.toString().padStart(4, '0'))
        fun two(value: Int) { if (value < 10) append('0'); append(value) }
        two(time.monthValue); two(time.dayOfMonth); two(time.hour); two(time.minute); two(time.second)
        append(" +0000")
    }
}

/** Text safe inside XML: markup characters escaped, characters XML does not allow left out. */
private fun xmlEscaped(text: String): String {
    if (text.none { it == '&' || it == '<' || it == '>' || it == '"' || it < ' ' }) return text
    return buildString(text.length + 16) {
        text.forEach { char ->
            when {
                char == '&' -> append("&amp;")
                char == '<' -> append("&lt;")
                char == '>' -> append("&gt;")
                char == '"' -> append("&quot;")
                char < ' ' && char != '\t' && char != '\n' && char != '\r' -> Unit
                else -> append(char)
            }
        }
    }
}

private class StalkerPage<T>(val entries: List<T>, val totalItems: Int?, val maxPageItems: Int?)

/** Streams a Stalker answer (`{"js":{"total_items":..,"data":[{..},..]}}` or without `js`). */
private fun <T : Any> readStalkerPage(input: InputStream, map: (Map<String, String>) -> T?): StalkerPage<T> {
    var entries: List<T> = emptyList()
    var total: Int? = null
    var perPage: Int? = null
    JsonReader(InputStreamReader(input.withoutBom(), Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        fun readBody() {
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "js" -> when (reader.peek()) {
                        JsonToken.BEGIN_OBJECT -> readBody()
                        // get_genres answers {"js":[{..},..]}.
                        JsonToken.BEGIN_ARRAY -> entries = reader.readObjectArray(map)
                        else -> reader.skipValue()
                    }
                    "data" -> entries = if (reader.peek() == JsonToken.BEGIN_ARRAY) reader.readObjectArray(map) else {
                        reader.skipValue(); emptyList()
                    }
                    "total_items" -> total = reader.nextScalar()?.toIntOrNull()
                    "max_page_items" -> perPage = reader.nextScalar()?.toIntOrNull()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        if (reader.peek() == JsonToken.BEGIN_OBJECT) readBody()
    }
    return StalkerPage(entries, total, perPage)
}

// endregion

// region Streaming JSON helpers

/** Some PHP panels start their answers with a UTF-8 byte-order mark, which JSON readers reject. */
private fun InputStream.withoutBom(): InputStream {
    val buffered = this as? java.io.BufferedInputStream ?: java.io.BufferedInputStream(this, 8 * 1024)
    buffered.mark(3)
    if (!(buffered.read() == 0xEF && buffered.read() == 0xBB && buffered.read() == 0xBF)) buffered.reset()
    return buffered
}

/** Reads a top-level array of objects (or `{"data":[...]}`), mapping each object's plain fields. */
private fun <T : Any> readObjects(input: InputStream, arrayName: String = "data", map: (Map<String, String>) -> T?): List<T> =
    JsonReader(InputStreamReader(input.withoutBom(), Charsets.UTF_8)).use { reader ->
        reader.isLenient = true
        when (reader.peek()) {
            JsonToken.BEGIN_ARRAY -> reader.readObjectArray(map)
            JsonToken.BEGIN_OBJECT -> {
                var result: List<T> = emptyList()
                reader.beginObject()
                while (reader.hasNext()) {
                    if (reader.nextName() == arrayName && reader.peek() == JsonToken.BEGIN_ARRAY) {
                        result = reader.readObjectArray(map)
                    } else {
                        reader.skipValue()
                    }
                }
                reader.endObject()
                result
            }
            else -> emptyList()
        }
    }

private fun <T : Any> JsonReader.readObjectArray(map: (Map<String, String>) -> T?): List<T> {
    val result = ArrayList<T>()
    val fields = HashMap<String, String>(16)
    beginArray()
    while (hasNext()) {
        if (peek() != JsonToken.BEGIN_OBJECT) {
            skipValue()
            continue
        }
        fields.clear()
        beginObject()
        while (hasNext()) {
            val name = nextName()
            nextScalar()?.let { fields[name] = it }
        }
        endObject()
        // The map is reused for the next entry: [map] must not keep it.
        map(fields)?.let(result::add)
    }
    endArray()
    return result
}

/** A string, number or boolean as trimmed text; null (and skipped) for blanks, nulls and nested values. */
private fun JsonReader.nextScalar(): String? = when (peek()) {
    JsonToken.STRING, JsonToken.NUMBER -> nextString().trim().takeIf(String::isNotBlank)
    JsonToken.BOOLEAN -> nextBoolean().toString()
    else -> {
        skipValue()
        null
    }
}

// endregion

/** A load failure the screen shows as a translated message. */
enum class LiveTvError {
    InvalidUrl, NoChannels, LoadFailed, FileEmpty, FileNoChannels,
    StalkerRequired, StalkerInvalidUrl, StalkerNoChannels, StalkerFailed, StalkerToken,
    /** The list loaded, but some of the portal's pages did not: a notice, not a failed load. */
    StalkerIncomplete,
    XtreamRequired, XtreamInvalidUrl, XtreamNoChannels, XtreamFailed,
    /** The guide link given with a source is not a web link. */
    GuideInvalidUrl,
}

internal class LiveTvException(val error: LiveTvError) : Exception(error.name)
