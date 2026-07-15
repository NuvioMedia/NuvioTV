package com.nuvio.tv.core.deeplink

import com.nuvio.tv.domain.deeplink.AppDeepLink
import java.net.URI
import java.net.URLDecoder

object DeepLinkParser {
    fun parse(url: String): AppDeepLink? {
        val parsedUrl = runCatching { URI(url.trim()) }.getOrNull() ?: return null
        val scheme = parsedUrl.scheme?.lowercase().orEmpty()
        if (scheme == "stremio") {
            val host = parsedUrl.host?.lowercase().orEmpty()
            return if (looksLikeAddonHost(host)) {
                customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
            } else {
                null
            }
        }
        if (scheme != "nuvio") return null

        val host = parsedUrl.host?.lowercase().orEmpty()
        val pathSegments = parsedUrl.rawPath
            ?.split("/")
            .orEmpty()
            .mapNotNull { segment -> decode(segment).trim().takeIf(String::isNotBlank) }

        return when (host) {
            "meta" -> parseMetaFromParameters(parsedUrl) ?: parseMetaFromPath(pathSegments)
            "detail", "details", "open", "watch" -> parseMetaFromPath(pathSegments)
            "movie", "movies", "series", "show", "shows", "tv" -> {
                val type = normalizeMediaType(host)
                val id = pathSegments.firstOrNull()?.let(::normalizeId).orEmpty()
                if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
            }
            "imdb", "tmdb" -> parseProviderMetaDeepLink(host, pathSegments, parsedUrl)
            "auth" -> null
            else -> {
                if (looksLikeAddonHost(host)) {
                    customSchemeToHttpsUrl(url, scheme)?.let(AppDeepLink::AddonInstall)
                } else {
                    null
                }
            }
        }
    }

    private fun parseMetaFromParameters(parsedUrl: URI): AppDeepLink.Meta? {
        val parameters = queryParameters(parsedUrl)
        val type = firstParameter(parameters, "type", "mediaType", "media_type")
            ?.let(::normalizeMediaType)
            .orEmpty()
        val id = firstParameter(parameters, "id", "imdb", "imdbId", "imdb_id")
            ?.let(::normalizeId)
            ?: firstParameter(parameters, "tmdb", "tmdbId", "tmdb_id")
                ?.let { "tmdb:${it.removePrefix("tmdb:").trim()}" }
            ?: ""
        return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
    }

    private fun parseMetaFromPath(pathSegments: List<String>): AppDeepLink.Meta? {
        if (pathSegments.size < 2) return null
        val type = normalizeMediaType(pathSegments[0])
        val id = normalizeId(pathSegments[1])
        return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
    }

    private fun parseProviderMetaDeepLink(
        provider: String,
        pathSegments: List<String>,
        parsedUrl: URI
    ): AppDeepLink.Meta? {
        val first = pathSegments.firstOrNull().orEmpty()
        val second = pathSegments.getOrNull(1).orEmpty()
        val firstAsType = normalizeMediaType(first)
        val queryType = firstParameter(queryParameters(parsedUrl), "type", "mediaType", "media_type")
            ?.let(::normalizeMediaType)
            .orEmpty()
        val type = firstAsType.ifBlank { queryType }
        val rawId = if (firstAsType.isNotBlank()) second else first
        val id = when (provider) {
            "tmdb" -> rawId.removePrefix("tmdb:").trim().takeIf(String::isNotBlank)?.let { "tmdb:$it" }
            else -> normalizeId(rawId).takeIf(String::isNotBlank)
        }.orEmpty()
        return if (type.isBlank() || id.isBlank()) null else AppDeepLink.Meta(type = type, id = id)
    }

    private fun queryParameters(parsedUrl: URI): Map<String, String> {
        return parsedUrl.rawQuery
            ?.split("&")
            .orEmpty()
            .mapNotNull { pair ->
                val index = pair.indexOf("=")
                if (index < 0) return@mapNotNull null
                val key = decode(pair.substring(0, index)).trim()
                val value = decode(pair.substring(index + 1)).trim()
                if (key.isBlank() || value.isBlank()) null else key to value
            }
            .toMap()
    }

    private fun firstParameter(parameters: Map<String, String>, vararg keys: String): String? {
        return keys.firstNotNullOfOrNull { key ->
            parameters[key]?.trim()?.takeIf(String::isNotBlank)
        }
    }

    private fun normalizeMediaType(value: String): String {
        return when (value.trim().lowercase()) {
            "movie", "movies", "film", "films" -> "movie"
            "series", "show", "shows", "tv", "tvshow", "tvshows" -> "series"
            else -> ""
        }
    }

    private fun normalizeId(value: String): String {
        return value.trim()
            .removePrefix("imdb:")
            .takeIf(String::isNotBlank)
            .orEmpty()
    }

    private fun looksLikeAddonHost(host: String): Boolean {
        return host.contains('.') ||
            host.equals("localhost", ignoreCase = true) ||
            host.any(Char::isDigit)
    }

    private fun customSchemeToHttpsUrl(url: String, scheme: String): String? {
        val prefix = "$scheme://"
        val rest = url.trim()
            .takeIf { it.startsWith(prefix, ignoreCase = true) }
            ?.substring(prefix.length)
            ?.takeIf { it.isNotBlank() && !it.startsWith("/") }
            ?: return null
        return "https://$rest"
    }

    private fun decode(value: String): String {
        return runCatching { URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)
    }
}
