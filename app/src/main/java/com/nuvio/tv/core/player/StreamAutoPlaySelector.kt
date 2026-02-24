package com.nuvio.tv.core.player

import com.nuvio.tv.data.local.StreamAutoPlayMode
import com.nuvio.tv.data.local.StreamAutoPlaySource
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.Stream

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
                if (pattern.isBlank()) return null
 
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


                candidateStreams.firstOrNull { stream ->
                    val url = stream.getStreamUrl() ?: return@firstOrNull false

                    val searchableText = buildString {
                        append(stream.addonName).append(' ')
                        append(stream.name.orEmpty()).append(' ')
                        append(stream.title.orEmpty()).append(' ')
                        append(stream.description.orEmpty()).append(' ')
                        append(url)
                    }
                    
                    // Must match user include pattern
                    if (!userRegex.containsMatchIn(searchableText)) return@firstOrNull false

                    // Must NOT match user exclusion pattern (if any)
                    if (excludeRegex != null && excludeRegex.containsMatchIn(searchableText)) {
                        return@firstOrNull false
                    }

                    true


                }
            }
        }
    }
}
