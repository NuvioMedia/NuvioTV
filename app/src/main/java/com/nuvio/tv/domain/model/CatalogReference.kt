package com.nuvio.tv.domain.model

fun catalogTypeKey(value: String): String {
    val rawType = value.trim()
    val type = ContentType.fromString(rawType)
    return if (type == ContentType.UNKNOWN) rawType else type.toApiString()
}

fun catalogTypesMatch(first: String, second: String): Boolean =
    catalogTypeKey(first) == catalogTypeKey(second)

fun collectionCatalogKey(addonId: String, type: String, catalogId: String): String =
    "$addonId|${catalogTypeKey(type)}|$catalogId"

fun List<CatalogDescriptor>.findCollectionCatalog(type: String, catalogId: String): CatalogDescriptor? {
    val candidates = filter { it.id == catalogId }
    return candidates.firstOrNull { it.apiType == type.trim() }
        ?: candidates.singleOrNull { catalogTypesMatch(it.apiType, type) }
}

fun Collection.withResolvedCatalogTypes(addons: List<Addon>): Collection = copy(
    folders = folders.map { folder ->
        folder.copy(sources = folder.sources.map { source ->
            if (source is AddonCatalogCollectionSource) {
                val catalog = addons.firstOrNull { it.id == source.addonId && it.isActive }
                    ?.catalogs?.findCollectionCatalog(source.type, source.catalogId)
                if (catalog != null) source.copy(type = catalog.apiType) else source
            } else source
        })
    }
)
