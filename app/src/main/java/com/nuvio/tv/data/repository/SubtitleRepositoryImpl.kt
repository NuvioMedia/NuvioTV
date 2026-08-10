package com.nuvio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.network.safeApiCall
import com.nuvio.tv.data.local.AddonPreferences
import com.nuvio.tv.data.remote.api.AddonApi
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.Subtitle
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.SubtitleRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject

class SubtitleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val api: AddonApi,
    private val addonRepository: AddonRepositoryImpl
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 20_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        onProgress: ((completed: Int, total: Int, addonName: String?) -> Unit)?,
        onSubtitlesEmitted: ((List<Subtitle>) -> Unit)?
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$id, videoId=$videoId")
        
        // Get installed addons
        val addons = try {
            addonRepository.getInstalledAddons().first().enabledAddons()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get installed addons", e)
            return@withContext emptyList()
        }

        // Filter addons that support subtitles resource
        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(addon, resource, requestType, id)
            }
        }
        
        Log.d(TAG, "Found ${subtitleAddons.size} subtitle addons: ${subtitleAddons.map { it.name }}")

        if (subtitleAddons.isEmpty()) {
            return@withContext emptyList()
        }

        val total = subtitleAddons.size
        val completedCount = AtomicInteger(0)
        onProgress?.invoke(0, total, null)

        val accumulatedSubtitles = java.util.Collections.synchronizedList(mutableListOf<Subtitle>())

        // Fetch subtitles from all addons in parallel and stream results immediately
        val result = supervisorScope {
            subtitleAddons.map { addon ->
                async {
                    val addonStartMs = System.currentTimeMillis()
                    val subtitles = try {
                        withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                            fetchSubtitlesFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
                        emptyList()
                    }
                    onProgress?.invoke(completedCount.incrementAndGet(), total, addon.displayName)
                    if (!subtitles.isNullOrEmpty()) {
                        val snapshot = synchronized(accumulatedSubtitles) {
                            accumulatedSubtitles.addAll(subtitles)
                            accumulatedSubtitles.toList()
                        }
                        if (onSubtitlesEmitted != null) {
                            withContext(Dispatchers.Main.immediate) {
                                onSubtitlesEmitted.invoke(snapshot)
                            }
                        }
                        Log.d(
                            TAG,
                            "Subtitle fetch done for addon=${addon.name} count=${subtitles.size} in ${System.currentTimeMillis() - addonStartMs}ms"
                        )
                        subtitles
                    } else {
                        if (subtitles == null) {
                            Log.w(
                                TAG,
                                "Subtitle fetch timed out for addon=${addon.name} after ${PER_ADDON_TIMEOUT_MS}ms"
                            )
                        }
                        emptyList()
                    }
                }
            }.awaitAll().flatten()
        }
        Log.d(
            TAG,
            "Subtitle fetch completed total=${result.size} fromAddons=${subtitleAddons.size} in ${System.currentTimeMillis() - startedAtMs}ms"
        )
        result
    }

    private fun canonicalSubtitleType(type: String): String {
        return if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()
    }
    
    private fun supportsType(addon: Addon, resource: com.nuvio.tv.domain.model.AddonResource, type: String, id: String): Boolean {
        // Check if type is supported (normalizing "tv" and "series" to be equivalent)
        if (resource.types.isNotEmpty()) {
            val reqType = canonicalSubtitleType(type)
            val matchesType = resource.types.any { resType ->
                canonicalSubtitleType(resType) == reqType
            }
            if (!matchesType) return false
        }
        
        // Check if id prefix is supported (check resource first, then fallback to addon top-level idPrefixes)
        val prefixes = resource.idPrefixes?.takeIf { it.isNotEmpty() }
            ?: addon.idPrefixes.takeIf { it.isNotEmpty() }
        if (prefixes != null && prefixes.isNotEmpty()) {
            return prefixes.any { prefix -> id.startsWith(prefix) }
        }
        
        return true
    }

    private fun isSubtitleResource(name: String): Boolean {
        return name.equals("subtitles", ignoreCase = true) ||
            name.equals("subtitle", ignoreCase = true)
    }
    
    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val actualId: String = if (normalizedType == "series" && !videoId.isNullOrEmpty()) {
            videoId
        } else {
            videoId?.takeIf { it.isNotBlank() } ?: id
        }
        
        val encodedType = encodePathSegment(normalizedType)
        val encodedActualId = encodePathSegment(actualId)

        // Build the subtitle URL with optional extra parameters
        val rawBaseUrl = addon.baseUrl.trimEnd('/')
        val queryStart = rawBaseUrl.indexOf('?')
        val basePath = if (queryStart >= 0) rawBaseUrl.substring(0, queryStart).trimEnd('/') else rawBaseUrl
        val baseQuery = if (queryStart >= 0) rawBaseUrl.substring(queryStart) else ""
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            "$basePath/subtitles/$encodedType/$encodedActualId/$extraParams.json$baseQuery"
        } else {
            "$basePath/subtitles/$encodedType/$encodedActualId.json$baseQuery"
        }
        
        Log.d(TAG, "Fetching subtitles from ${addon.name}: $subtitleUrl")
        
        return try {
            when (val result = safeApiCall(context) { api.getSubtitles(subtitleUrl) }) {
                is NetworkResult.Success -> {
                    val subtitles = result.data.subtitles?.mapNotNull { dto ->
                        val url = dto.url?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                        val lang = dto.lang?.takeIf { it.isNotBlank() } ?: dto.language?.takeIf { it.isNotBlank() } ?: "und"
                        val subId = dto.id?.takeIf { it.isNotBlank() } ?: "$lang-${url.hashCode()}"
                        Subtitle(
                            id = subId,
                            url = url,
                            lang = lang,
                            addonName = addon.displayName,
                            addonLogo = addon.logo
                        )
                    } ?: emptyList()
                    
                    Log.d(TAG, "Got ${subtitles.size} subtitles from ${addon.name}")
                    subtitles
                }
                is NetworkResult.Error -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: code=${result.code} message=${result.message}")
                    emptyList()
                }
                NetworkResult.Loading -> emptyList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }
    
    private fun buildExtraParams(
        videoHash: String?,
        videoSize: Long?,
        filename: String?
    ): String {
        val params = mutableListOf<String>()
        
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let {
            params.add("filename=${encodePathSegment(it)}")
        }
        
        return if (params.isNotEmpty()) {
            params.joinToString("&")
        } else {
            ""
        }
    }

    private fun encodePathSegment(value: String): String {
        return java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun String?.isNullOfBlank(): Boolean = this == null || this.isBlank()
}
