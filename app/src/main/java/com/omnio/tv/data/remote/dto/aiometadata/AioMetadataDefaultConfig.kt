package com.omnio.tv.data.remote.dto.aiometadata

import android.content.Context
import com.omnio.tv.R
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the default AIOMetadata configuration from the bundled template
 * (res/raw/aiometadata_default_config.json), injects user-supplied API keys,
 * and hard-codes the free RPDB key so poster ratings work out of the box.
 *
 * The template is the "Stremio Perfect Setup" guide config:
 * https://luckynumb3rs.github.io/stremio-perfect-setup/guide/4-AIOMetadata-Setup/
 */
object AioMetadataDefaultConfig {

    private const val RPDB_FREE_KEY = "t0-free-rpdb"

    fun build(context: Context, userApiKeys: Map<String, String>): AioConfigInnerDto {
        val rawJson = context.resources
            .openRawResource(R.raw.aiometadata_default_config)
            .bufferedReader()
            .use { it.readText() }

        val json = JSONObject(rawJson)

        // Routing providers (e.g. "movie" → "tmdb") plus any booleans in the block.
        val routingProviders = jsonObjectToMap(json.optJSONObject("providers") ?: JSONObject())
        // Add boolean toggle states for the known provider keys so the Android UI
        // shows TMDB and TVDB as enabled on first load.
        val providers: Map<String, Any?> = routingProviders +
            mapOf("tmdb" to true, "tvdb" to true)

        // Default API keys from template (RPDB is already set to the free key there,
        // but we also enforce it here in case the template ever changes).
        val defaultApiKeys = mutableMapOf<String, String>()
        json.optJSONObject("apiKeys")?.keys()?.forEach { key ->
            val v = json.getJSONObject("apiKeys").optString(key, "")
            if (v.isNotBlank()) defaultApiKeys[key] = v
        }
        if (defaultApiKeys["rpdb"].isNullOrBlank()) {
            defaultApiKeys["rpdb"] = RPDB_FREE_KEY
        }

        // User-supplied keys override defaults (TMDB / TVDB come from the user).
        val finalApiKeys = (defaultApiKeys + userApiKeys).filterValues { it.isNotBlank() }

        // Catalogs are inside the config object.
        val catalogs = jsonArrayToList(json.optJSONArray("catalogs") ?: JSONArray())
            .filterIsInstance<Map<String, Any?>>()

        // Everything else becomes the settings catch-all (language, search config, etc.).
        val excludedKeys = setOf("providers", "apiKeys", "catalogs")
        val settings = json.keys().asSequence()
            .filter { it !in excludedKeys }
            .associate { key -> key to jsonToKotlin(json.get(key)) }

        return AioConfigInnerDto(
            providers = providers,
            apiKeys = finalApiKeys,
            catalogs = catalogs,
            settings = settings,
        )
    }

    private fun jsonToKotlin(value: Any): Any? = when (value) {
        is JSONObject -> jsonObjectToMap(value)
        is JSONArray -> jsonArrayToList(value)
        JSONObject.NULL -> null
        else -> value
    }

    private fun jsonObjectToMap(obj: JSONObject): Map<String, Any?> =
        obj.keys().asSequence().associate { key -> key to jsonToKotlin(obj.get(key)) }

    private fun jsonArrayToList(arr: JSONArray): List<Any?> =
        (0 until arr.length()).map { i -> jsonToKotlin(arr.get(i)) }
}
