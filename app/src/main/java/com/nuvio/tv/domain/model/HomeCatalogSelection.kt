package com.nuvio.tv.domain.model

/** Catalog provenance and playback provider selection are separate choices. */
sealed interface HomeCatalogSelection {
    data class Addon(val addonId: String, val addonBaseUrl: String) : HomeCatalogSelection
    data class Plugin(val source: PluginSourceRef) : HomeCatalogSelection
}

sealed interface HomeStreamSelection {
    data object All : HomeStreamSelection
    data class Plugin(val source: PluginSourceRef) : HomeStreamSelection
}

data class HomeCatalogSelections(
    val catalog: HomeCatalogSelection? = null,
    val streams: HomeStreamSelection = HomeStreamSelection.All
) {
    /** Called for an explicit catalog change, not when restoring saved selections. */
    fun selectCatalog(
        catalog: HomeCatalogSelection,
        availableStreamSources: Set<PluginSourceRef>
    ): HomeCatalogSelections {
        val source = (catalog as? HomeCatalogSelection.Plugin)?.source
        return copy(
            catalog = catalog,
            streams = if (source != null && source in availableStreamSources) {
                HomeStreamSelection.Plugin(source)
            } else {
                HomeStreamSelection.All
            }
        )
    }

    fun selectStreams(streams: HomeStreamSelection): HomeCatalogSelections = copy(streams = streams)
}

/** All covers eligible stream plugins only; addon execution is outside this selection model. */
fun HomeStreamSelection.includes(source: PluginSourceRef, capabilities: PluginCapabilities): Boolean =
    capabilities.supportsStreams && (this == HomeStreamSelection.All ||
        this is HomeStreamSelection.Plugin && this.source == source)

/** Never forward a catalog provider's URL or opaque identifier to another provider. */
fun PluginContentRef.forStreamSource(
    source: PluginSourceRef,
    selection: HomeStreamSelection,
    capabilities: PluginCapabilities
): PluginContentRef? = takeIf { this.source == source && selection.includes(source, capabilities) }
