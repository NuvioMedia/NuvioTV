package com.nuvio.tv.core.usenet

import java.io.StringReader
import java.time.ZonedDateTime
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.serialization.Serializable
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class UsenetSearchRequest(
    val imdbId: String? = null,
    val tmdbId: String? = null,
    val tvdbId: String? = null,
    val title: String? = null,
    val year: Int? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val series: Boolean = false
)

@Serializable
data class NewznabCapabilities(
    val movieParams: Set<String> = setOf("imdbid"),
    val tvParams: Set<String> = setOf("imdbid", "season", "ep"),
    val searchAvailable: Boolean = true,
    val limit: Int = 100
)

data class UsenetRelease(
    val title: String,
    val nzbUrl: String,
    val size: Long = 0,
    val publishedAt: Long = 0,
    val indexerId: String = "",
    val indexerName: String = "",
    val passworded: Boolean = false
) {
    val resolution: Int get() = Regex("(?i)(?<!\\d)(2160|1080|720|480|360)p?(?!\\d)")
        .find(title)?.groupValues?.get(1)?.toInt() ?: if (Regex("(?i)\\b4k\\b").containsMatchIn(title)) 2160 else 0
    val lowQuality: Boolean get() = Regex("(?i)(?:^|[ ._-])(?:cam|hdcam|telesync|telecine|hdts|ts|tc|scr|dvdscr)(?:$|[ ._-])")
        .containsMatchIn(title)
}

data class NewznabPage(val releases: List<UsenetRelease>, val total: Int = 0)

/** Protocol functions are independent of Android and never include credentials in errors. */
object NewznabProtocol {
    fun apiUrl(indexer: UsenetIndexer, action: String): HttpUrl.Builder = indexer.apiUrl.toHttpUrl()
        .newBuilder().setQueryParameter("apikey", indexer.apiKey)
        .setQueryParameter("t", action).setQueryParameter("o", "xml")

    /** [wholeSeason] drops the episode from an ID search so one request covers a season. */
    fun searchUrl(indexer: UsenetIndexer, request: UsenetSearchRequest,
        caps: NewznabCapabilities, offset: Int = 0, wholeSeason: Boolean = false): HttpUrl? {
        val params = if (request.series) caps.tvParams else caps.movieParams
        val builder = apiUrl(indexer, if (request.series) "tvsearch" else "movie")
        val idParam = when {
            request.series && !("season" in params && "ep" in params) -> null
            request.imdbId != null && "imdbid" in params -> "imdbid" to request.imdbId.removePrefix("tt")
            // TVDB is the TV identifier Newznab indexers support most widely.
            request.series && request.tvdbId != null && "tvdbid" in params -> "tvdbid" to request.tvdbId
            request.tmdbId != null && "tmdbid" in params -> "tmdbid" to request.tmdbId
            else -> null
        }
        // Season-wide text searches are too broad to share between episodes.
        if (wholeSeason && (!request.series || idParam == null)) return null
        if (idParam != null) {
            builder.setQueryParameter(idParam.first, idParam.second)
        } else {
            val title = request.title?.takeIf { it.isNotBlank() } ?: return null
            if ("q" !in params) {
                if (!caps.searchAvailable) return null
                builder.setQueryParameter("t", "search")
            }
            val query = if (request.series) {
                title + if (request.season != null && request.episode != null) {
                    " S%02dE%02d".format(Locale.ROOT, request.season, request.episode)
                } else ""
            } else title
            builder.setQueryParameter("q", query)
            if (!request.series && request.year != null && "year" in params) {
                builder.setQueryParameter("year", request.year.toString())
            }
        }
        if (request.series) {
            // Never turn an episode lookup into an unbounded whole-series ID lookup.
            if (request.season == null || request.episode == null) return null
            if (idParam != null && !("season" in params && "ep" in params)) return null
            if ("season" in params) builder.setQueryParameter("season", request.season.toString())
            if ("ep" in params && !wholeSeason) builder.setQueryParameter("ep", request.episode.toString())
        }
        return builder.setQueryParameter("cat", if (request.series) "5000" else "2000")
            .setQueryParameter("limit", caps.limit.coerceIn(1, 100).toString())
            .setQueryParameter("offset", offset.toString()).build()
    }

    fun capabilities(xml: String): NewznabCapabilities {
        val root = document(xml)
        require(root.localName == "caps") { "Invalid indexer capabilities response" }
        fun params(tag: String): Set<String> {
            val element = root.getElementsByTagName(tag).item(0) as? Element ?: return emptySet()
            if (element.getAttribute("available") != "yes") return emptySet()
            return element.getAttribute("supportedParams").split(',').map { it.trim().lowercase() }.toSet()
        }
        val limits = root.getElementsByTagName("limits").item(0) as? Element
        val search = root.getElementsByTagName("search").item(0) as? Element
        return NewznabCapabilities(params("movie-search"), params("tv-search"),
            search?.getAttribute("available") == "yes",
            limits?.getAttribute("max")?.toIntOrNull()?.coerceIn(1, 100) ?: 100)
    }

    fun releases(xml: String, indexer: UsenetIndexer): NewznabPage {
        val root = document(xml)
        require(root.localName == "rss") { "Invalid indexer search response" }
        val items = root.getElementsByTagName("item")
        val results = (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as Element
            fun text(tag: String) = item.getElementsByTagName(tag).item(0)?.textContent?.trim().orEmpty()
            val attrs = item.getElementsByTagNameNS("*", "attr")
            val attributes = (0 until attrs.length).associate { j ->
                val attr = attrs.item(j) as Element
                attr.getAttribute("name").lowercase() to attr.getAttribute("value")
            }
            val enclosures = item.getElementsByTagName("enclosure")
            val enclosure = (0 until enclosures.length).map { enclosures.item(it) as Element }
                .firstOrNull { it.getAttribute("type").contains("nzb", ignoreCase = true) }
            val download = enclosure?.getAttribute("url")?.takeIf { it.isNotBlank() }
                ?: text("link").takeIf { it.isNotBlank() && enclosures.length == 0 } ?: return@mapNotNull null
            val url = indexer.apiUrl.toHttpUrl().resolve(download) ?: return@mapNotNull null
            val title = text("title").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val published = listOfNotNull(attributes["usenetdate"], text("pubDate")).firstNotNullOfOrNull { date ->
                runCatching { ZonedDateTime.parse(date, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli() }.getOrNull()
                    ?: runCatching { Instant.parse(date).toEpochMilli() }.getOrNull()
                    ?: date.toLongOrNull()?.takeIf { it > 0 && it < Long.MAX_VALUE / 1000 }?.let {
                        if (it < 100000000000L) it * 1000 else it
                    }
            } ?: 0
            UsenetRelease(title, url.toString(),
                (attributes["size"]?.toLongOrNull() ?: enclosure?.getAttribute("length")?.toLongOrNull() ?: 0).coerceAtLeast(0),
                published, indexer.id, indexer.name, attributes["password"]?.lowercase() in setOf("1", "2", "true", "yes"))
        }
        val response = root.getElementsByTagNameNS("*", "response").item(0) as? Element
        return NewznabPage(results, response?.getAttribute("total")?.toIntOrNull() ?: results.size)
    }

    /**
     * Aggregators may fall back to text search. Reject contradictory filename metadata.
     * [requireNumbering] rejects unnumbered series releases, which only an episode search vouches for.
     */
    fun matches(release: UsenetRelease, request: UsenetSearchRequest, idSearch: Boolean,
        requireNumbering: Boolean = false): Boolean {
        if (!idSearch) {
            fun normalize(value: String) = value.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
            val title = request.title?.let(::normalize)?.takeIf { it.isNotEmpty() } ?: return false
            if (!" ${normalize(release.title)} ".contains(" $title ")) return false
            if (!request.series && request.year != null) {
                // Year-like title words (Blade Runner 2049, 1917) are not release years.
                val titleWords = title.split(' ').toSet()
                val years = Regex("(?<![\\p{L}\\p{N}])((?:19|20)\\d{2})(?![\\p{L}\\p{N}])").findAll(release.title)
                    .map { it.groupValues[1] }.toList()
                if (request.year.toString() !in years && years.any { it !in titleWords }) return false
            }
        }
        if (request.series) {
            val range = "(?:(?:[ ._]*E|[ ._]*-[ ._]*E?)(\\d{1,3})(?=$|[ ._-]))?"
            val episodes = listOf(
                Regex("(?i)S(\\d{1,3})[ ._-]*E(\\d{1,3})(?!\\d)$range"),
                Regex("(?i)(?<!\\d)(\\d{1,2})x(\\d{1,3})(?!\\d)$range")
            ).flatMap { it.findAll(release.title).toList() }
            if (episodes.isNotEmpty()) return episodes.any {
                val season = it.groupValues[1].toInt()
                val first = it.groupValues[2].toInt()
                val last = it.groupValues[3].toIntOrNull() ?: first
                season == request.season && request.episode?.let { episode -> episode in first..last } == true
            }
            val seasons = Regex("(?i)(?:^|[ ._-])(?:S|Season[ ._-]*)(\\d{1,3})(?:$|[ ._-])")
                .findAll(release.title).map { it.groupValues[1].toInt() }.toList()
            if (seasons.isNotEmpty()) return request.season in seasons
            // Obfuscated ID-matched releases rely on the engine's file selection.
            return idSearch && !requireNumbering
        }
        return true
    }

    /**
     * Keeps one copy of a release found by several indexers: the one from the highest
     * indexer priority (then list order) that passes the filters. Names are compared
     * ignoring case and punctuation, since indexers write separators differently.
     */
    fun withoutDuplicates(releases: List<UsenetRelease>, config: UsenetSourceConfiguration,
        now: Long = System.currentTimeMillis()): List<UsenetRelease> {
        val order = config.indexers.mapIndexed { i, indexer -> indexer.id to i }.toMap()
        fun name(release: UsenetRelease) =
            release.title.lowercase(Locale.ROOT).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
        return releases.groupBy(::name).values.map { copies ->
            // A preferred copy hidden by a filter (no size, passworded) must not hide the others.
            copies.filter { arrange(listOf(it), config, now).isNotEmpty() }.ifEmpty { copies }
                .minBy { order[it.indexerId] ?: Int.MAX_VALUE }
        }
    }

    fun arrange(releases: List<UsenetRelease>, config: UsenetSourceConfiguration,
        now: Long = System.currentTimeMillis()): List<UsenetRelease> {
        val priority = config.indexers.mapIndexed { i, indexer -> indexer.id to i }.toMap()
        val filtered = releases.filter {
            !it.passworded && (!config.excludeLowQuality || !it.lowQuality) &&
                (config.minResolution == 0 || it.resolution >= config.minResolution) &&
                (config.maxSizeGb == 0 || it.size in 1..config.maxSizeGb.toLong() * 1024 * 1024 * 1024) &&
                (config.maxAgeDays == 0 || it.publishedAt > 0 && now - it.publishedAt <= config.maxAgeDays.toLong() * 86400000)
        }
        val primary = when (config.sort) {
            UsenetSort.QUALITY -> compareByDescending<UsenetRelease> { it.resolution }
            UsenetSort.LARGEST -> compareByDescending<UsenetRelease> { it.size }
            UsenetSort.SMALLEST -> compareBy<UsenetRelease> { if (it.size > 0) it.size else Long.MAX_VALUE }
            UsenetSort.NEWEST -> compareByDescending<UsenetRelease> { it.publishedAt }
            UsenetSort.INDEXER -> compareBy<UsenetRelease> { priority[it.indexerId] ?: Int.MAX_VALUE }
        }
        return filtered.sortedWith(primary.thenBy { priority[it.indexerId] ?: Int.MAX_VALUE }
            .thenByDescending { it.resolution }.thenByDescending { it.size }.thenBy { it.title }.thenBy { it.nzbUrl })
            .distinctBy { it.nzbUrl }.take(config.maxResults)
    }

    private fun document(xml: String): Element {
        require(!xml.contains("<!DOCTYPE", true) && !xml.contains("<!ENTITY", true)) { "Invalid indexer XML" }
        val factory = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true; isExpandEntityReferences = false }
        val root = factory.newDocumentBuilder().parse(InputSource(StringReader(xml))).documentElement
        if (root.localName == "error") {
            val code = root.getAttribute("code")
            if (code in setOf("429", "500", "501")) throw NewznabQuotaException()
            if (code in setOf("100", "101", "102")) throw NewznabAuthException()
            error(when (code) {
                "200", "201", "202", "203" -> "Indexer does not support this search"
                else -> "Indexer returned an API error"
            })
        }
        return root
    }

    /** Some indexers pair an HTTP error status with a Newznab quota or authentication error document. */
    fun throwIfKnownError(xml: String) {
        try { document(xml) }
        catch (e: NewznabQuotaException) { throw e }
        catch (e: NewznabAuthException) { throw e }
        catch (_: Exception) { }
    }
}

/** The indexer reported its request or download quota as spent (Newznab 429/500/501). */
class NewznabQuotaException : IllegalStateException("Indexer rate limit reached")

/** The indexer rejected the API key or account (Newznab 100-102). */
class NewznabAuthException : IllegalStateException("Indexer authentication failed")
