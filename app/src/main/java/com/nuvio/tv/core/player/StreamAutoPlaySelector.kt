package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream
import java.net.HttpURLConnection
import java.net.URL

object StreamAutoPlaySelector {
    fun orderAddonStreams(
        streams: List<AddonStreams>,
        installedOrder: List<String>
    ): List<AddonStreams> {
        if (streams.isEmpty()) return streams

        val (addonEntries, pluginEntries) = streams.partition { it.addonName in installedOrder }
        val orderedAddons = addonEntries.sortedBy { installedOrder.indexOf(it.addonName) }
        return orderedAddons + pluginEntries
    }

    private fun resolvePlayableUrl(stream: Stream): String? {
        val url = stream.getStreamUrl() ?: return null

        // Pixeldrain is the ONLY host that needs special resolving
        if ("pixeldrain" in url) {
            val id = url.substringAfterLast("/")
            val infoUrl = "https://pixeldrain.dev/api/file/$id/info"

            val infoJson = runCatching { URL(infoUrl).readText() }.getOrNull() ?: return null

            if (!infoJson.contains("\"success\":true")) return null
            if (!infoJson.contains("\"mime_type\":\"video")) return null

            return "https://pixeldrain.dev/api/file/$id?download"
        }

        // Everything else: return as-is
        return url
    }



    private fun urlWorks(url: String): Boolean {
        val lower = url.lowercase()

        // Skip probing for signed or tokened URLs
        if (listOf("token=", "expires=", "signature=", "sig=", "auth=", "key=", "hash=", "x-amz-", "hdnts=", "cf_")
                .any { lower.contains(it) }) {
            return true
        }

        // Safe HEAD probe for everything else
        return runCatching {
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.requestMethod = "HEAD"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000
            connection.instanceFollowRedirects = true
            connection.connect()
            connection.responseCode in 200..399
        }.getOrElse { false }
    }




    fun selectAutoPlayStream(
        streams: List<Stream>,
        mode: StreamAutoPlayMode,
        regexPattern: String,
        source: StreamAutoPlaySource,
        installedAddonNames: Set<String>,
        selectedAddons: Set<String>,
        selectedPlugins: Set<String>,
        preferredBingeGroup: String? = null
    ): Stream? {
        if (streams.isEmpty()) return null

        val sourceScopedStreams = when (source) {
            StreamAutoPlaySource.ALL_SOURCES -> streams
            StreamAutoPlaySource.INSTALLED_ADDONS_ONLY -> streams.filter { it.addonName in installedAddonNames }
            StreamAutoPlaySource.ENABLED_PLUGINS_ONLY -> streams.filter { it.addonName !in installedAddonNames }
        }
        val candidateStreams = sourceScopedStreams.filter { stream ->
            val isAddonStream = stream.addonName in installedAddonNames
            if (isAddonStream) {
                selectedAddons.isEmpty() || stream.addonName in selectedAddons
            } else {
                selectedPlugins.isEmpty() || stream.addonName in selectedPlugins
            }
        }
        if (candidateStreams.isEmpty()) return null
        if (mode == StreamAutoPlayMode.MANUAL) return null

        val targetBingeGroup = preferredBingeGroup?.trim().orEmpty()
        if (targetBingeGroup.isNotEmpty()) {
            val bingeGroupMatch = candidateStreams.firstOrNull { stream ->
                stream.behaviorHints?.bingeGroup == targetBingeGroup && stream.getStreamUrl() != null
            }
            if (bingeGroupMatch != null) return bingeGroupMatch
        }

        return when (mode) {
            StreamAutoPlayMode.MANUAL -> null
            StreamAutoPlayMode.FIRST_STREAM -> candidateStreams.firstOrNull { it.getStreamUrl() != null }
            StreamAutoPlayMode.REGEX_MATCH -> {
                val pattern = regexPattern.trim()

                // Skip regex entirely if pattern is blank
                if (pattern.isBlank()) {
                    return null
                }
 
                // Try to compile the user regex
                val userRegex = runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.getOrNull() ?: return null

                // Auto-extract exclusion patterns from negative lookaheads
                val exclusionMatches = Regex("\\(\\?![^)]*?\\(([^)]+)\\)").findAll(pattern)

                val exclusionWords = exclusionMatches
                    .flatMap { match -> match.groupValues[1].split("|") }
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                    .toList()

                val excludeRegex = if (exclusionWords.isNotEmpty()) {
                    Regex("\\b(${exclusionWords.joinToString("|")})\\b", RegexOption.IGNORE_CASE)
                } else null

                // 1. Build list of ALL regex‑matching streams
                val matchingStreams = candidateStreams.filter { stream ->
                    val url = stream.getStreamUrl() ?: return@filter false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }

                    // Must match include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@filter false

                    // Must NOT match exclusion pattern
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@filter false
                    }

                    true
                }

                if (matchingStreams.isEmpty()) return null

                // 2. Try each matching stream until one works
                for (stream in matchingStreams) {
                    val resolved = resolvePlayableUrl(stream) ?: continue
                    println("Trying resolved stream: $resolved")
                    if (urlWorks(resolved)) return stream

                }
                // None worked
                null
            }

        }
    }
}
