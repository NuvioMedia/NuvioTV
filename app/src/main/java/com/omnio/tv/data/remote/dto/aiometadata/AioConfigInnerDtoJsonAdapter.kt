package com.omnio.tv.data.remote.dto.aiometadata

import com.omnio.tv.domain.model.AioConfigInnerDto
import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.JsonReader
import com.squareup.moshi.JsonWriter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import java.lang.reflect.Type

/**
 * Custom (de)serializer for [AioConfigInnerDto].
 *
 * Upstream cedya77/aiometadata expects the config wire format with all
 * miscellaneous fields at the root:
 *
 *   { "providers": {...}, "apiKeys": {...}, "catalogs": [...],
 *     "language": "en", "mal": {...}, "tmdb": {...}, "search": {...},
 *     "tvdbSeasonType": "default", ... }
 *
 * The web `/configure` UI (configure/src/components/sections/ProvidersSettings.tsx
 * and SearchSettings.tsx) reads those as `config.mal.*`, `config.tmdb.*`, etc.
 * — without optional chaining in several spots — so wrapping them in a
 * nested "settings" object (as earlier client builds did) crashes the Meta
 * Providers and Search tabs with "Cannot read properties of undefined".
 *
 * The DTO keeps a convenient [AioConfigInnerDto.settings] map for internal
 * ergonomics; this adapter spreads it at the root on the wire.
 *
 * Legacy rescue: when reading a config that was stored by an older build,
 * any top-level "settings" object is unwrapped and merged into the catch-all
 * map (root-level entries win on conflict). Subsequent writes re-flatten,
 * so the stale server-side shape heals after the next save.
 */
class AioConfigInnerDtoJsonAdapter(moshi: Moshi) : JsonAdapter<AioConfigInnerDto>() {

    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    override fun fromJson(reader: JsonReader): AioConfigInnerDto {
        val raw = mapAdapter.fromJson(reader) ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val providers = (raw["providers"] as? Map<String, Any?>) ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val apiKeys = (raw["apiKeys"] as? Map<String, Any?>)
            ?.mapValues { (_, v) -> v?.toString().orEmpty() }
            ?: emptyMap()

        @Suppress("UNCHECKED_CAST")
        val catalogs = (raw["catalogs"] as? List<Any?>)
            ?.filterIsInstance<Map<String, Any?>>()
            ?: emptyList()

        val settings = linkedMapOf<String, Any?>()
        (raw["settings"] as? Map<*, *>)?.forEach { (k, v) ->
            val key = k?.toString() ?: return@forEach
            settings[key] = v
        }
        for ((k, v) in raw) {
            if (k in RESERVED_KEYS) continue
            settings[k] = v
        }

        return AioConfigInnerDto(
            providers = providers,
            apiKeys = apiKeys,
            catalogs = catalogs,
            settings = settings,
        )
    }

    override fun toJson(writer: JsonWriter, value: AioConfigInnerDto?) {
        if (value == null) {
            writer.nullValue()
            return
        }
        val out = linkedMapOf<String, Any?>()
        out["providers"] = value.providers
        out["apiKeys"] = value.apiKeys
        out["catalogs"] = value.catalogs
        for ((k, v) in value.settings) {
            if (k in RESERVED_KEYS) continue
            out[k] = v
        }
        mapAdapter.toJson(writer, out)
    }

    companion object Factory : JsonAdapter.Factory {
        private val RESERVED_KEYS = setOf("providers", "apiKeys", "catalogs", "settings")

        override fun create(
            type: Type,
            annotations: Set<Annotation>,
            moshi: Moshi,
        ): JsonAdapter<*>? {
            if (annotations.isNotEmpty()) return null
            if (Types.getRawType(type) != AioConfigInnerDto::class.java) return null
            return AioConfigInnerDtoJsonAdapter(moshi)
        }
    }
}
