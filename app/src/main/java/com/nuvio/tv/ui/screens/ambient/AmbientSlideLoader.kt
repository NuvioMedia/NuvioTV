package com.nuvio.tv.ui.screens.ambient

import android.content.Context
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.core.tmdb.MetaPreviewEnricher
import com.nuvio.tv.core.util.filterReleasedItems
import com.nuvio.tv.data.local.LayoutPreferenceDataStore
import com.nuvio.tv.data.local.TmdbSettingsDataStore
import com.nuvio.tv.data.mediaserver.ServerCatalog
import com.nuvio.tv.domain.model.AmbientModeSettings
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.model.skipStep
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.ui.screens.home.shouldShowOnHome
import com.nuvio.tv.ui.util.localizedGenreLabel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

private const val ITEMS_PER_CATALOG = 10
private const val MAX_SLIDES = 30
private const val MAX_GENRES = 3

@Singleton
class AmbientSlideLoader @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonRepository: AddonRepository,
    private val serverCatalog: ServerCatalog,
    private val catalogRepository: CatalogRepository,
    private val layoutPreferenceDataStore: LayoutPreferenceDataStore,
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val metaPreviewEnricher: MetaPreviewEnricher
) {
    suspend fun load(selectedKeys: List<String>): List<AmbientSlide> = coroutineScope {
        val addons = addonRepository.getInstalledAddons().first().enabledAddons() +
            serverCatalog.addons.first()
        val catalogs = addons.ambientCatalogs()
        val keys = selectedKeys.ifEmpty { layoutPreferenceDataStore.heroCatalogSelections.first() }
        val chosen = keys
            .mapNotNull { key -> catalogs.firstOrNull { it.key == key } }
            .ifEmpty { catalogs.filter { it.catalog.shouldShowOnHome() }.take(1) }
            .take(AmbientModeSettings.MAX_CATALOGS)
        val hideUnreleased = layoutPreferenceDataStore.hideUnreleasedContent.first()

        val rows = chosen.map { entry -> async { fetchItems(entry, hideUnreleased) } }.awaitAll()
        val items = rows.interleave().distinctBy { "${it.apiType}:${it.id}" }.take(MAX_SLIDES)

        val tmdbSettings = tmdbSettingsDataStore.settings.value
        val useTmdb = tmdbSettings.enabled &&
            (tmdbSettings.useArtwork || tmdbSettings.useBasicInfo || tmdbSettings.useDetails)
        val enriched = if (useTmdb) metaPreviewEnricher.enrich(items, tmdbSettings) else items

        enriched.mapNotNull { it.toSlide() }.shuffled()
    }

    private suspend fun fetchItems(entry: AmbientCatalog, hideUnreleased: Boolean): List<MetaPreview> {
        val result = catalogRepository.getCatalog(
            addonBaseUrl = entry.addon.baseUrl,
            addonId = entry.addon.id,
            addonName = entry.addon.displayName,
            catalogId = entry.catalog.id,
            catalogName = entry.catalog.name,
            type = entry.catalog.apiType,
            skip = 0,
            skipStep = entry.catalog.skipStep(),
            supportsSkip = entry.catalog.supportsExtra("skip")
        ).first { it !is NetworkResult.Loading }
        val row = (result as? NetworkResult.Success)?.data ?: return emptyList()
        val visible = if (hideUnreleased) row.filterReleasedItems(LocalDate.now()) else row
        return visible.items.take(ITEMS_PER_CATALOG)
    }

    private fun List<List<MetaPreview>>.interleave(): List<MetaPreview> {
        val longest = maxOfOrNull { it.size } ?: 0
        return (0 until longest).flatMap { index -> mapNotNull { it.getOrNull(index) } }
    }

    private fun MetaPreview.toSlide(): AmbientSlide? {
        val backdrop = background?.takeIf { it.isNotBlank() }
            ?: landscapePoster?.takeIf { it.isNotBlank() }
            ?: return null
        return AmbientSlide(
            id = "$apiType:$id",
            title = name,
            backdropUrl = backdrop,
            logoUrl = logo?.takeIf { it.isNotBlank() },
            genres = genres.take(MAX_GENRES).map { localizedGenreLabel(context, it) }
        )
    }
}
