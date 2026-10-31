package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.R
import com.nuvio.tv.core.streams.supportsResource
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.enabledAddons
import com.nuvio.tv.domain.repository.AddonRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class ServerLibraryRef(
    val connection: ServerConnection,
    val library: ServerLibrary
) {
    val title: String
        get() = "${connection.name} · ${library.name}"
}

internal fun ServerTitle.catalogPreview(addons: List<Addon>): MetaPreview {
    if (preview.apiType == ServerMediaKind.COLLECTION.contentType) return preview
    val ids = externalIds.catalogIds()
    val id = addons.firstNotNullOfOrNull { addon ->
        ids.firstOrNull { addon.supportsResource("meta", preview.apiType, it) }
    } ?: ids.firstOrNull { it.startsWith("tmdb:") } ?: return preview
    return preview.copy(id = id, imdbId = externalIds.imdb)
}

@Singleton
class ServerCatalog @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: ServerRepository,
    private val addonRepository: AddonRepository
) {
    val detailsLoaded = MutableSharedFlow<ServerItemDetails>(extraBufferCapacity = DETAILS_BUFFER)

    val addons: Flow<List<Addon>> = repository.uiState
        .map { state -> state.enabledConnections.mapNotNull(::addonFor) }
        .distinctUntilChanged()

    val searchAddons: Flow<List<Addon>> = addons.map { addons ->
        addons.map { addon ->
            addon.copy(
                catalogs = addon.catalogs.filter {
                    it.id != RESUME_ID &&
                        it.apiType != ServerMediaKind.COLLECTION.contentType &&
                        it.apiType != ServerMediaKind.MIXED.contentType
                }
            )
        }.filter { it.catalogs.isNotEmpty() }
    }

    fun libraries(): List<ServerLibraryRef> =
        repository.enabledConnections().flatMap { connection ->
            connection.selectedLibraries.map { ServerLibraryRef(connection, it) }
        }

    fun titleLibraries(): List<ServerLibraryRef> =
        libraries().filter { it.library.kind != ServerMediaKind.COLLECTION }

    fun sourceLabel(connectionId: String): String =
        repository.connection(connectionId)?.let(repository::sourceLabel).orEmpty()

    suspend fun catalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): CatalogRow {
        val connectionId = connectionId(addonBaseUrl) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val connection = repository.connection(connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val query = extraArgs["search"]?.trim()?.takeIf { it.isNotEmpty() }
        val page = when {
            catalogId == RESUME_ID -> resumePage(connection, skip)
            catalogId.startsWith(COLLECTION_PREFIX) -> pageOf(connection, skip) {
                repository.call(connection.id) { provider, session ->
                    provider.collectionPage(session, catalogId.removePrefix(COLLECTION_PREFIX), skip, PAGE_SIZE)
                }
            }
            catalogId.startsWith(SEARCH_PREFIX) -> searchPage(connection, catalogId.removePrefix(SEARCH_PREFIX), query, skip)
            else -> {
                val library = connection.libraries.firstOrNull { it.id == catalogId }
                    ?: throw ServerException(ServerFailure.NOT_FOUND)
                pageOf(connection, skip) {
                    repository.call(connection.id) { provider, session ->
                        provider.libraryPage(session, library, skip, PAGE_SIZE)
                    }
                }
            }
        }
        return CatalogRow(
            addonId = addonId,
            addonName = addonName,
            addonBaseUrl = addonBaseUrl,
            catalogId = catalogId,
            catalogName = catalogName,
            type = ContentType.fromString(type),
            rawType = type,
            items = page.items,
            isLoading = false,
            hasMore = page.nextSkip != null,
            currentPage = skip / PAGE_SIZE,
            supportsSkip = true,
            skipStep = PAGE_SIZE,
            nextSkip = page.nextSkip ?: skip,
            extraArgs = extraArgs
        )
    }

    suspend fun collectionRow(ref: ServerItemRef): CatalogRow {
        val connection = repository.connection(ref.connectionId) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val name = details(ref).meta.name
        val row = CatalogRow(
            addonId = addonId(connection.id),
            addonName = repository.sourceLabel(connection),
            addonBaseUrl = baseUrl(connection.id),
            catalogId = COLLECTION_PREFIX + ref.itemId,
            catalogName = name,
            type = ContentType.UNKNOWN,
            rawType = ServerMediaKind.COLLECTION.contentType,
            items = emptyList()
        )
        return page(row, skip = 0)
    }

    suspend fun row(connectionId: String, catalogId: String): CatalogRow {
        val addon = repository.connection(connectionId)?.let(::addonFor) ?: throw ServerException(ServerFailure.NOT_FOUND)
        val descriptor = addon.catalogs.firstOrNull { it.id == catalogId } ?: throw ServerException(ServerFailure.NOT_FOUND)
        val row = CatalogRow(
            addonId = addon.id,
            addonName = addon.displayName,
            addonBaseUrl = addon.baseUrl,
            catalogId = descriptor.id,
            catalogName = descriptor.name,
            type = descriptor.type,
            rawType = descriptor.apiType,
            items = emptyList()
        )
        return page(row, skip = 0)
    }

    suspend fun page(row: CatalogRow, skip: Int): CatalogRow =
        catalog(row.addonBaseUrl, row.addonId, row.addonName, row.catalogId, row.catalogName, row.apiType, skip, row.extraArgs)

    suspend fun details(ref: ServerItemRef): ServerItemDetails =
        repository.call(ref.connectionId) { provider, session -> provider.details(session, ref.itemId) }
            .also { detailsLoaded.tryEmit(it) }

    private fun addonFor(connection: ServerConnection): Addon? {
        val provider = repository.provider(connection) ?: return null
        val libraries = connection.selectedLibraries
        if (libraries.isEmpty()) return null
        val resume = CatalogDescriptor(
            type = ContentType.MOVIE,
            id = RESUME_ID,
            name = "${connection.name} · ${context.getString(R.string.servers_resume_row)}",
            showInHome = true,
            hasExplicitShowInHome = true
        ).takeIf { provider.supports(ServerCapability.USER_STATE_READ) }
        val catalogs = libraries.map { library ->
            CatalogDescriptor(
                type = library.kind.domainType(),
                rawType = library.kind.contentType,
                id = library.id,
                name = "${connection.name} · ${library.name}",
                extra = listOf(CatalogExtra(name = "skip")),
                showInHome = true,
                hasExplicitShowInHome = true
            )
        }
        val searches = SEARCH_KINDS
            .filter { kind -> provider.supports(ServerCapability.SEARCH) && libraries.any { it.holds(kind) } }
            .map { kind ->
                CatalogDescriptor(
                    type = kind.domainType(),
                    rawType = kind.contentType,
                    id = SEARCH_PREFIX + kind.contentType,
                    name = "${connection.name} · ${context.getString(kind.searchLabel())}",
                    extra = listOf(CatalogExtra(name = "search", isRequired = true))
                )
            }
        val label = repository.sourceLabel(connection)
        return Addon(
            id = addonId(connection.id),
            name = provider.displayName,
            displayName = label,
            version = if (connection.useCatalogMetadata) "metadata" else "native",
            description = null,
            logo = null,
            baseUrl = baseUrl(connection.id),
            catalogs = listOfNotNull(resume) + catalogs + searches,
            types = listOf(ContentType.MOVIE, ContentType.SERIES),
            resources = listOf(AddonResource(name = "catalog", types = emptyList(), idPrefixes = null))
        )
    }

    private suspend fun resumePage(connection: ServerConnection, skip: Int): Page {
        if (skip > 0) return Page(emptyList(), null)
        val presenter = presenter(connection)
        val items = repository.call(connection.id) { provider, session -> provider.resumeItems(session, PAGE_SIZE) }
            .map(presenter)
        return Page(items, null)
    }

    private suspend fun searchPage(connection: ServerConnection, contentType: String, query: String?, skip: Int): Page {
        val kind = ServerMediaKind.fromContentType(contentType)
        if (query == null || kind == null || skip > 0) return Page(emptyList(), null)
        val libraries = connection.selectedLibraries(kind)
        val presenter = presenter(connection)
        val items = repository.call(connection.id) { provider, session ->
            if (!provider.supports(ServerCapability.SEARCH)) throw ServerException(ServerFailure.UNSUPPORTED)
            provider.search(session, kind, libraries, query, SEARCH_LIMIT)
        }.map(presenter)
        return Page(items, null)
    }

    private fun ServerMediaKind.searchLabel(): Int =
        if (this == ServerMediaKind.SERIES) R.string.type_series_plural else R.string.type_movies

    private suspend fun pageOf(
        connection: ServerConnection,
        skip: Int,
        load: suspend () -> ServerPage<ServerTitle>
    ): Page {
        val page = load()
        val loaded = skip + page.items.size
        val hasMore = page.items.isNotEmpty() && (page.totalCount?.let { loaded < it } ?: (page.items.size >= PAGE_SIZE))
        return Page(page.items.map(presenter(connection)), loaded.takeIf { hasMore })
    }

    private suspend fun presenter(connection: ServerConnection): (ServerTitle) -> MetaPreview {
        if (!connection.useCatalogMetadata) return ServerTitle::preview
        val addons = addonRepository.getInstalledAddons().first().enabledAddons()
        return { title -> title.catalogPreview(addons) }
    }

    private class Page(val items: List<MetaPreview>, val nextSkip: Int?)

    companion object {
        const val PAGE_SIZE = 50
        private const val SEARCH_LIMIT = 30
        private const val DETAILS_BUFFER = 16
        private const val RESUME_ID = "resume"
        private const val COLLECTION_PREFIX = "collection:"
        private const val SEARCH_PREFIX = "search."
        private val SEARCH_KINDS = listOf(ServerMediaKind.MOVIE, ServerMediaKind.SERIES)
        private const val BASE_URL_PREFIX = "nuvio-server://"
        private const val ADDON_ID_PREFIX = "server."

        fun isServerAddon(baseUrl: String?): Boolean = baseUrl?.startsWith(BASE_URL_PREFIX) == true

        fun isServerAddonId(addonId: String?): Boolean = addonId?.startsWith(ADDON_ID_PREFIX) == true

        fun isCollection(id: String?, type: String?): Boolean =
            type == ServerMediaKind.COLLECTION.contentType && ServerItemRef.isServerId(id)

        fun isServerKey(key: String): Boolean = key.startsWith(ADDON_ID_PREFIX) || key.startsWith(BASE_URL_PREFIX)

        fun connectionId(baseUrl: String): String? = baseUrl.takeIf(::isServerAddon)?.removePrefix(BASE_URL_PREFIX)

        fun baseUrl(connectionId: String): String = BASE_URL_PREFIX + connectionId

        fun addonId(connectionId: String): String = ADDON_ID_PREFIX + connectionId

        fun connectionIdFromAddonId(addonId: String): String? = addonId.takeIf(::isServerAddonId)?.removePrefix(ADDON_ID_PREFIX)
    }
}
