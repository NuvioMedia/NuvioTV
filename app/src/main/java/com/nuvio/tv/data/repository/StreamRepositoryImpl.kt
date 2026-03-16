package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.R
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.core.plugin.PluginManager
import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.data.mapper.toDomain
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonStreams
import com.nuvio.tv.domain.model.ProxyHeaders
import com.nuvio.tv.domain.model.Stream
import com.nuvio.tv.domain.model.StreamBehaviorHints
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.StreamRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import java.net.URLEncoder
import javax.inject.Inject

private const val TAG = "StreamRepositoryImpl"

class StreamRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepository,
    private val pluginManager: PluginManager,
    private val tmdbService: TmdbService
) : StreamRepository {
    private enum class StreamFailureKind {
        MISSING,
        REQUEST_FAILED
    }

    private data class StreamAttemptFailure(
        val addonName: String,
        val kind: StreamFailureKind,
        val detail: String
    )

    override fun getStreamsFromAllAddons(
        type: String,
        videoId: String,
        season: Int?,
        episode: Int?
    ): Flow<NetworkResult<List<AddonStreams>>> = flow {
        emit(NetworkResult.Loading)

        try {
            val addons = addonRepository.getInstalledAddons().first()
            
            // Filter addons that support streams for this type
            val streamAddons = addons.filter { addon ->
                addon.supportsStreamResource(type)
            }

            // Convert IMDB ID to TMDB ID if needed for plugins
            val tmdbId = tmdbService.ensureTmdbId(videoId, type)
            Log.d(TAG, "Video ID: $videoId -> TMDB ID: $tmdbId (type: $type)")
            val attemptedAddonNames = streamAddons.map { it.displayName }
            val attemptedFailures = java.util.Collections.synchronizedList(
                mutableListOf<StreamAttemptFailure>()
            )

            // Accumulate results as they arrive
            val accumulatedResults = mutableListOf<AddonStreams>()

            coroutineScope {
                // Channel to receive results as they complete
                val resultChannel = Channel<AddonStreams>(Channel.UNLIMITED)
                
                // Track number of pending jobs
                val totalJobs = streamAddons.size + (if (tmdbId != null) 1 else 0)
                var completedJobs = 0

                // Launch addon jobs
                streamAddons.forEach { addon ->
                    launch {
                        try {
                            val streamsResult = getStreamsFromAddon(addon.baseUrl, type, videoId)
                            when (streamsResult) {
                                is NetworkResult.Success -> {
                                    if (streamsResult.data.isNotEmpty()) {
                                        val namedStreams = streamsResult.data.map {
                                            it.copy(addonName = addon.displayName, addonLogo = addon.logo)
                                        }
                                        resultChannel.send(
                                            AddonStreams(
                                                addonName = addon.displayName,
                                                addonLogo = addon.logo,
                                                streams = namedStreams
                                            )
                                        )
                                    } else {
                                        attemptedFailures += buildMissingStreamFailure(addon)
                                    }
                                }
                                is NetworkResult.Error -> {
                                    attemptedFailures += buildAddonFailure(addon, streamsResult)
                                }
                                NetworkResult.Loading -> Unit
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Addon ${addon.name} failed: ${e.message}")
                            attemptedFailures += StreamAttemptFailure(
                                addonName = addon.displayName,
                                kind = StreamFailureKind.REQUEST_FAILED,
                                detail = e.message ?: "the addon request failed"
                            )
                        } finally {
                            completedJobs++
                            if (completedJobs >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Launch plugin jobs if we have TMDB ID - each scraper sends its own result
                if (tmdbId != null) {
                    launch {
                        try {
                            // Stream plugins individually
                            streamLocalPlugins(tmdbId, type, season, episode, resultChannel) {
                                completedJobs++
                                if (completedJobs >= totalJobs) {
                                    resultChannel.close()
                                }
                            }
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            Log.e(TAG, "Plugin execution failed: ${e.message}")
                            completedJobs++
                            if (completedJobs >= totalJobs) {
                                resultChannel.close()
                            }
                        }
                    }
                }

                // Handle case where there are no jobs
                if (totalJobs == 0) {
                    resultChannel.close()
                }

                // Emit results as they arrive
                for (result in resultChannel) {
                    accumulatedResults.add(result)
                    emit(NetworkResult.Success(accumulatedResults.toList()))
                    Log.d(TAG, "Emitted ${accumulatedResults.size} addon(s), latest: ${result.addonName} with ${result.streams.size} streams")
                }
            }

            // Emit final result (even if empty)
            if (accumulatedResults.isEmpty()) {
                val errorMessage = buildAggregateFailureMessage(
                    type = type,
                    id = videoId,
                    attemptedAddonNames = attemptedAddonNames,
                    failures = attemptedFailures.toList()
                )
                if (errorMessage != null) {
                    emit(NetworkResult.Error(errorMessage))
                } else {
                    emit(NetworkResult.Success(emptyList()))
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to fetch streams: ${e.message}", e)
            emit(NetworkResult.Error(e.message ?: "Failed to fetch streams"))
        }
    }

    /**
     * Stream local plugin results - each scraper sends results individually
     */
    private suspend fun streamLocalPlugins(
        tmdbId: String,
        type: String,
        season: Int?,
        episode: Int?,
        resultChannel: Channel<AddonStreams>,
        onComplete: () -> Unit
    ) {
        // Check if plugins are enabled
        if (!pluginManager.pluginsEnabled.first()) {
            Log.d(TAG, "Plugins are disabled")
            onComplete()
            return
        }

        // Normalize media type for plugins
        val mediaType = when (type.lowercase()) {
            "series", "tv", "show" -> "tv"
            else -> type.lowercase()
        }

        Log.d(TAG, "Streaming plugins for TMDB: $tmdbId, type: $mediaType")

        try {
            // Collect streaming results from each scraper
            pluginManager.executeScrapersStreaming(
                tmdbId = tmdbId,
                mediaType = mediaType,
                season = season,
                episode = episode
            ).collect { (scraperName, results) ->
                if (results.isNotEmpty()) {
                    val addonStreams = AddonStreams(
                        addonName = scraperName,
                        addonLogo = null,
                        streams = results.map { result ->
                            val baseTitle = result.title.takeIf { it.isNotBlank() }
                            val baseName = result.name?.takeIf { it.isNotBlank() }
                            val quality = result.quality?.takeIf { it.isNotBlank() }

                            val displayTitle = buildString {
                                append(baseTitle ?: baseName ?: scraperName)
                                if (!quality.isNullOrBlank() && !(baseTitle ?: "").contains(quality)) {
                                    append(" ").append(quality)
                                }
                            }.takeIf { it.isNotBlank() }

                            val displayName = buildString {
                                append(baseName ?: baseTitle ?: scraperName)
                                if (!quality.isNullOrBlank() && !(baseName ?: "").contains(quality)) {
                                    append(" - ").append(quality)
                                }
                            }.takeIf { it.isNotBlank() }

                            Stream(
                                name = displayName,
                                title = displayTitle,
                                url = result.url,
                                addonName = scraperName,
                                addonLogo = null,
                                description = buildDescription(result),
                                behaviorHints = result.headers?.let { headers ->
                                    StreamBehaviorHints(
                                        notWebReady = null,
                                        bingeGroup = null,
                                        countryWhitelist = null,
                                        proxyHeaders = ProxyHeaders(request = headers, response = null)
                                    )
                                },
                                infoHash = result.infoHash,
                                fileIdx = null,
                                ytId = null,
                                externalUrl = null
                            )
                        }
                    )
                    resultChannel.send(addonStreams)
                    Log.d(TAG, "Streamed ${results.size} results from $scraperName")
                }
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to stream plugins: ${e.message}", e)
        } finally {
            onComplete()
        }
    }

    /**
     * Build a description string from scraper result
     */
    private fun buildDescription(result: com.nuvio.tv.domain.model.LocalScraperResult): String? {
        val parts = mutableListOf<String>()
        result.quality?.let { parts.add(it) }
        result.size?.let { parts.add(it) }
        result.language?.let { parts.add(it) }
        return if (parts.isNotEmpty()) parts.joinToString(" • ") else null
    }

    override suspend fun getStreamsFromAddon(
        baseUrl: String,
        type: String,
        videoId: String
    ): NetworkResult<List<Stream>> {
        val cleanBaseUrl = baseUrl.trimEnd('/')
        val encodedType = encodePathSegment(type)
        val encodedVideoId = encodePathSegment(videoId)
        val streamUrl = "$cleanBaseUrl/stream/$encodedType/$encodedVideoId.json"
        Log.d(TAG, "Fetching streams type=$type videoId=$videoId url=$streamUrl")

        // First, get addon info for name and logo
        val addonResult = addonRepository.fetchAddon(baseUrl)
        val addonName = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.displayName
            else -> "Unknown"
        }
        val addonLogo = when (addonResult) {
            is NetworkResult.Success -> addonResult.data.logo
            else -> null
        }

        return when (val result = safeApiCall { api.getStreams(streamUrl) }) {
            is NetworkResult.Success -> {
                val streams = result.data.streams?.map { 
                    it.toDomain(addonName, addonLogo) 
                } ?: emptyList()
                Log.d(TAG, "Streams success addon=$addonName count=${streams.size} url=$streamUrl")
                NetworkResult.Success(streams)
            }
            is NetworkResult.Error -> {
                Log.w(
                    TAG,
                    "Streams failed addon=$addonName code=${result.code} message=${result.message} url=$streamUrl"
                )
                result
            }
            NetworkResult.Loading -> NetworkResult.Loading
        }
    }

    /**
     * Check if addon supports stream resource for the given type
     */
    private fun Addon.supportsStreamResource(type: String): Boolean {
        return resources.any { resource ->
            resource.name == "stream" && 
            (resource.types.isEmpty() || resource.types.contains(type))
        }
    }

    private fun buildMissingStreamFailure(addon: Addon): StreamAttemptFailure {
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.MISSING,
            detail = "returned no streams for this id"
        )
    }

    private fun buildAddonFailure(addon: Addon, error: NetworkResult.Error): StreamAttemptFailure {
        if (error.code == 404 || error.message.equals("Not Found", ignoreCase = true)) {
            return buildMissingStreamFailure(addon)
        }
        val normalizedReason = when {
            error.message.contains("Unable to resolve host", ignoreCase = true) ->
                "could not reach the addon server"
            error.message.contains("Failed to connect", ignoreCase = true) ->
                "connection to the addon failed"
            error.message.contains("timeout", ignoreCase = true) ->
                "the addon request timed out"
            error.message.contains("CLEARTEXT communication", ignoreCase = true) ->
                "the addon uses an insecure HTTP connection blocked by Android"
            error.message.isBlank() ->
                "the addon request failed"
            else -> error.message.replaceFirstChar { char ->
                if (char.isLowerCase()) char.titlecase() else char.toString()
            }
        }
        val httpSuffix = error.code?.let { " (HTTP $it)" } ?: ""
        return StreamAttemptFailure(
            addonName = addon.displayName,
            kind = StreamFailureKind.REQUEST_FAILED,
            detail = "$normalizedReason$httpSuffix"
        )
    }

    private fun buildAggregateFailureMessage(
        type: String,
        id: String,
        attemptedAddonNames: List<String>,
        failures: List<StreamAttemptFailure>
    ): String? {
        if (attemptedAddonNames.isEmpty()) {
            return context.getString(R.string.error_stream_no_supported_addon, type)
        }

        val triedAddons = attemptedAddonNames.joinToString(", ")
        val missingOnly = failures.isNotEmpty() && failures.all { it.kind == StreamFailureKind.MISSING }
        if (failures.isEmpty() || missingOnly) {
            return context.getString(R.string.error_stream_tried_none, triedAddons, id, type)
        }

        val issueSummary = failures
            .filter { it.kind == StreamFailureKind.REQUEST_FAILED }
            .distinctBy { it.addonName to it.detail }
            .take(3)
            .joinToString("; ") { "${it.addonName}: ${it.detail}" }

        return if (issueSummary.isBlank()) {
            context.getString(R.string.error_stream_tried_generic, triedAddons, id, type)
        } else {
            context.getString(R.string.error_stream_tried_issues, triedAddons, id, type, issueSummary)
        }
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
