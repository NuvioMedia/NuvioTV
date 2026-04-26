package com.nuvio.tv.core.plugin.cloudstream

import android.util.Log
import com.nuvio.tv.domain.model.ExternalPluginEntry
import com.nuvio.tv.domain.model.ExternalRepoManifest
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ExternalRepoParser"

/**
 * Result of parsing an external repository URL.
 */
data class ExternalRepoParseResult(
    val name: String,
    val description: String?,
    val plugins: List<ExternalPluginEntry>
)

/**
 * Parses external extension repository formats.
 *
 * Supports two formats:
 * 1. Repo manifest with `pluginLists` URLs pointing to separate plugins.json files
 * 2. Direct plugins array (list of [ExternalPluginEntry])
 */
@Singleton
class ExternalRepoParser @Inject constructor(
    private val moshi: Moshi
) {
    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val repoManifestAdapter = moshi.adapter(ExternalRepoManifest::class.java)
    private val pluginListType = Types.newParameterizedType(List::class.java, ExternalPluginEntry::class.java)
    private val pluginListAdapter = moshi.adapter<List<ExternalPluginEntry>>(pluginListType)

    /**
     * Try to parse the given URL as an external repository.
     * Returns null if the content doesn't match any known external format.
     */
    suspend fun tryParse(url: String): ExternalRepoParseResult? = withContext(Dispatchers.IO) {
        val body = fetchBody(url) ?: return@withContext null
        val trimmed = body.trim()

        // Try as repo manifest (has "pluginLists" key)
        if (trimmed.contains("\"pluginLists\"")) {
            try {
                val manifest = repoManifestAdapter.fromJson(trimmed)
                if (manifest != null && manifest.pluginLists.isNotEmpty()) {
                    Log.d(TAG, "Parsed as repo manifest: ${manifest.name}, ${manifest.pluginLists.size} plugin lists")
                    val allPlugins = coroutineScope {
                        manifest.pluginLists.map { listUrl ->
                            async {
                                val resolvedUrl = resolveUrl(url, listUrl)
                                fetchPluginList(resolvedUrl) ?: emptyList()
                            }
                        }.awaitAll().flatten()
                    }
                    return@withContext ExternalRepoParseResult(
                        name = manifest.name,
                        description = manifest.description,
                        plugins = allPlugins
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Not a repo manifest: ${e.message}")
            }
        }

        // Try as direct plugins array (has "internalName" or "tvTypes")
        if (trimmed.startsWith("[")) {
            try {
                val plugins = pluginListAdapter.fromJson(trimmed)
                if (!plugins.isNullOrEmpty() && plugins.first().internalName.isNotBlank()) {
                    Log.d(TAG, "Parsed as direct plugins list: ${plugins.size} plugins")
                    val repoName = inferRepoName(url)
                    return@withContext ExternalRepoParseResult(
                        name = repoName,
                        description = null,
                        plugins = plugins
                    )
                }
            } catch (e: Exception) {
                Log.d(TAG, "Not a direct plugins list: ${e.message}")
            }
        }

        null
    }

    private suspend fun fetchPluginList(url: String): List<ExternalPluginEntry>? = withContext(Dispatchers.IO) {
        val body = fetchBody(url) ?: return@withContext null
        try {
            pluginListAdapter.fromJson(body.trim())
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse plugin list from $url: ${e.message}")
            null
        }
    }

    private fun fetchBody(url: String): String? {
        return try {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", "NuvioTV/1.0")
                .build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "HTTP ${response.code} for $url")
                    return null
                }
                response.body?.string()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $url: ${e.message}")
            null
        }
    }

    private fun resolveUrl(baseUrl: String, relativeUrl: String): String {
        if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
            return relativeUrl
        }
        val base = baseUrl.substringBeforeLast("/")
        return "$base/$relativeUrl"
    }

    private fun inferRepoName(url: String): String {
        // Try to extract a meaningful name from the URL
        val path = url.substringAfter("://").substringBefore("?")
        val segments = path.split("/").filter { it.isNotBlank() }
        return segments.lastOrNull()?.removeSuffix(".json") ?: "External Repository"
    }
}
