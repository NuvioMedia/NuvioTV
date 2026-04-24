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
 *
 * Note: the built DTO groups non-routing/non-key/non-catalog template fields
 * under [AioConfigInnerDto.settings] for internal ergonomics. The custom
 * Moshi adapter flattens those entries up to the root on the wire, which is
 * the shape upstream's web `/configure` UI expects.
 */
object AioMetadataDefaultConfig {

    private const val RPDB_FREE_KEY = "t0-free-rpdb"
    const val TEMPLATE_VERSION_KEY = "nuvio_template_version"
    const val TEMPLATE_VERSION = "1"

    fun isTemplateApplied(config: AioConfigInnerDto): Boolean =
        config.settings[TEMPLATE_VERSION_KEY] != null

    fun build(context: Context, userApiKeys: Map<String, String>): AioConfigInnerDto {
        val rawJson = context.resources
            .openRawResource(R.raw.aiometadata_default_config)
            .bufferedReader()
            .use { it.readText() }

        val json = JSONObject(rawJson)

        // Routing providers (e.g. "movie" → "tmdb"). Keep this pure routing config —
        // NuvioTV toggle states go into settings under nuvio_provider_* keys.
        val providers: Map<String, Any?> = jsonObjectToMap(json.optJSONObject("providers") ?: JSONObject())

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
            settings = settings + mapOf(
                TEMPLATE_VERSION_KEY to TEMPLATE_VERSION,
                // Store initial provider enabled states here, not in providers (routing config).
                "nuvio_provider_tmdb" to true,
                "nuvio_provider_tvdb" to true,
            ),
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
