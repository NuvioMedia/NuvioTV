package com.nuvio.tv.data.mapper

import com.nuvio.tv.data.remote.dto.AddonManifestDto
import com.nuvio.tv.data.remote.dto.AddonBehaviorHintsDto
import com.nuvio.tv.data.remote.dto.CatalogDescriptorDto
import com.nuvio.tv.data.remote.dto.StremioAddonsConfigDto
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonBehaviorHints
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.StremioAddonsConfig

fun AddonManifestDto.toDomain(baseUrl: String): Addon {
    val manifestTypes = types.map { it.trim() }.filter { it.isNotEmpty() }
    return Addon(
        id = id,
        name = name,
        version = version,
        description = description,
        logo = logo,
        background = background,
        baseUrl = baseUrl,
        catalogs = catalogs.map { it.toDomain() },
        types = manifestTypes.map { ContentType.fromString(it) },
        rawTypes = manifestTypes,
        resources = parseResources(resources, manifestTypes),
        idPrefixes = idPrefixes,
        behaviorHints = behaviorHints?.toDomain(),
        stremioAddonsConfig = stremioAddonsConfig?.toDomain(),
        manifestLanguage = language,
        configVersion = configVersion,
        timestamp = timestamp
    )
}

fun CatalogDescriptorDto.toDomain(): CatalogDescriptor {
    val manifestType = type.trim()
    return CatalogDescriptor(
        type = ContentType.fromString(manifestType),
        rawType = manifestType,
        id = id,
        name = name,
        extra = parseCatalogExtras(extra),
        pageSize = pageSize,
        showInHome = showInHome == true,
        extraSupported = extraSupported.orEmpty(),
        extraRequired = extraRequired.orEmpty()
    )
}

private fun parseResources(resources: List<Any>, defaultTypes: List<String>): List<AddonResource> {
    return resources.mapNotNull { resource ->
        when (resource) {
            is String -> {
                // Simple resource format: "meta", "stream", etc.
                AddonResource(
                    name = resource,
                    types = defaultTypes,
                    idPrefixes = null
                )
            }
            is Map<*, *> -> {
                // Complex resource format with types and idPrefixes
                val name = resource["name"] as? String ?: return@mapNotNull null
                val types = (resource["types"] as? List<*>)?.filterIsInstance<String>() ?: defaultTypes
                val idPrefixes = (resource["idPrefixes"] as? List<*>)?.filterIsInstance<String>()
                AddonResource(
                    name = name,
                    types = types,
                    idPrefixes = idPrefixes
                )
            }
            else -> null
        }
    }
}

private fun parseCatalogExtras(rawExtras: List<Any>?): List<CatalogExtra> {
    return rawExtras.orEmpty().mapNotNull { raw ->
        when (raw) {
            is String -> {
                val name = raw.trim().lowercase()
                if (name.isBlank()) {
                    null
                } else {
                    CatalogExtra(name = name)
                }
            }
            is Map<*, *> -> {
                val name = (raw["name"] as? String)?.trim()?.lowercase().orEmpty()
                if (name.isBlank()) return@mapNotNull null

                val isRequired = when (val required = raw["isRequired"]) {
                    is Boolean -> required
                    is String -> required.equals("true", ignoreCase = true)
                    is Number -> required.toInt() != 0
                    else -> false
                }
                val options = (raw["options"] as? List<*>)?.mapNotNull { option ->
                    when (option) {
                        null -> null
                        is String -> option
                        else -> option.toString()
                    }
                }?.takeIf { it.isNotEmpty() }

                CatalogExtra(
                    name = name,
                    isRequired = isRequired,
                    options = options,
                    defaultValue = (raw["default"] as? String)?.takeIf { it.isNotBlank() },
                    optionsLimit = when (val value = raw["optionsLimit"]) {
                        is Number -> value.toInt()
                        is String -> value.toIntOrNull()
                        else -> null
                    }
                )
            }
            else -> null
        }
    }.distinct()
}

private fun AddonBehaviorHintsDto.toDomain(): AddonBehaviorHints {
    return AddonBehaviorHints(
        configurable = configurable,
        configurationRequired = configurationRequired,
        newEpisodeNotifications = newEpisodeNotifications
    )
}

private fun StremioAddonsConfigDto.toDomain(): StremioAddonsConfig {
    return StremioAddonsConfig(
        issuer = issuer?.takeIf { it.isNotBlank() },
        signature = signature?.takeIf { it.isNotBlank() }
    )
}
