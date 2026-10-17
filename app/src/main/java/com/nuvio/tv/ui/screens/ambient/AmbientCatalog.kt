package com.nuvio.tv.ui.screens.ambient

import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.catalogRowLegacyKey

data class AmbientCatalog(
    val key: String,
    val title: String,
    val source: String,
    val addon: Addon,
    val catalog: CatalogDescriptor
)

internal fun List<Addon>.ambientCatalogs(): List<AmbientCatalog> = flatMap { addon ->
    addon.catalogs
        .filterNot { catalog -> catalog.extra.any { it.isRequired } }
        .map { catalog ->
            AmbientCatalog(
                key = catalogRowLegacyKey(addon.id, catalog.apiType, catalog.id),
                title = catalog.name,
                source = addon.displayName,
                addon = addon,
                catalog = catalog
            )
        }
}
