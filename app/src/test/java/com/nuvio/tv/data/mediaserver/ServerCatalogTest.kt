package com.nuvio.tv.data.mediaserver

import android.content.Context
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.supportsExtra
import com.nuvio.tv.domain.repository.AddonRepository
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerCatalogTest {
    private val installedAddons = MutableStateFlow<List<Addon>>(emptyList())
    private val context = mockk<Context> {
        every { getString(any()) } returns "Continue watching"
    }
    private val addonRepository = mockk<AddonRepository> {
        every { getInstalledAddons() } returns installedAddons
    }

    private fun catalog(
        provider: FakeServerProvider = FakeServerProvider(),
        libraries: List<ServerLibrary> = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.SERIES_LIBRARY)
    ): Triple<ServerCatalog, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider, libraries = libraries)
        return Triple(ServerCatalog(context, repository, addonRepository), repository, connection)
    }

    private suspend fun ServerCatalog.page(connection: ServerConnection, catalogId: String, skip: Int, extra: Map<String, String> = emptyMap()) =
        catalog(
            addonBaseUrl = ServerCatalog.baseUrl(connection.id),
            addonId = ServerCatalog.addonId(connection.id),
            addonName = "Fake · Box",
            catalogId = catalogId,
            catalogName = "Box · Movies",
            type = "movie",
            skip = skip,
            extraArgs = extra
        )

    @Test
    fun paginatesLibraryWithOpaqueNumericIds() = runTest {
        val (catalog, _, connection) = catalog()

        val first = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0)
        assertEquals(ServerCatalog.PAGE_SIZE, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(ServerCatalog.PAGE_SIZE, first.nextSkip)
        assertEquals(ServerItemRef(connection.id, "0"), ServerItemRef.parse(first.items.first().id))

        val last = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 100)
        assertEquals(20, last.items.size)
        assertFalse(last.hasMore)
    }

    @Test
    fun exposesSelectedLibrariesAsAttributedAddonRows() = runTest {
        val (catalog, repository, connection) = catalog()
        repository.setLibrarySelected(connection.id, FakeServerProvider.SERIES_LIBRARY.id, false)

        val addon = catalog.addons.first().single()
        assertEquals(ServerCatalog.addonId(connection.id), addon.id)
        assertEquals("Fake · Box", addon.displayName)
        assertTrue(ServerCatalog.isServerAddon(addon.baseUrl))
        assertEquals(listOf("resume", "10", "search.movie"), addon.catalogs.map { it.id })
        assertEquals("Box · Movies", addon.catalogs.single { it.id == "10" }.name)
        assertEquals(listOf("10", "search.movie"), catalog.searchAddons.first().single().catalogs.map { it.id })
        assertEquals(listOf("search.movie"), addon.catalogs.filter { it.supportsExtra("search") }.map { it.id })
        assertTrue(addon.catalogs.last().extra.single().isRequired)
    }

    @Test
    fun catalogMetadataChangesTheLoadSignature() = runTest {
        val (catalog, repository, connection) = catalog()
        val native = catalog.addons.first().single().version

        repository.setCatalogMetadata(connection.id, true)

        assertTrue(native != catalog.addons.first().single().version)
    }

    @Test
    fun disabledConnectionsProvideNoRows() = runTest {
        val (catalog, repository, connection) = catalog()
        repository.setEnabled(connection.id, false)

        assertTrue(catalog.addons.first().isEmpty())
        assertTrue(catalog.libraries().isEmpty())
    }

    @Test
    fun searchesEachTypeOnceAndResumes() = runTest {
        val provider = FakeServerProvider()
        val (catalog, _, connection) = catalog(provider)

        val search = catalog.page(connection, "search.series", skip = 0, extra = mapOf("search" to "item"))
        assertEquals(listOf(ServerItemRef(connection.id, "7").encode()), search.items.map { it.id })
        assertFalse(search.hasMore)
        assertEquals(listOf(ServerMediaKind.SERIES to listOf(FakeServerProvider.SERIES_LIBRARY.id)), provider.searches)

        val resume = catalog.page(connection, "resume", skip = 0)
        assertEquals(listOf(ServerItemRef(connection.id, "3").encode()), resume.items.map { it.id })
    }

    @Test
    fun mixedLibrariesServeBothSearches() = runTest {
        val provider = FakeServerProvider()
        val mixed = ServerLibrary("90", "Everything", ServerMediaKind.MIXED)
        val (catalog, _, connection) = catalog(provider, libraries = listOf(mixed))

        val addon = catalog.addons.first().single()
        assertEquals(listOf("resume", "90", "search.movie", "search.series"), addon.catalogs.map { it.id })
        assertEquals(listOf("search.movie", "search.series"), catalog.searchAddons.first().single().catalogs.map { it.id })
        catalog.page(connection, "search.movie", skip = 0, extra = mapOf("search" to "item"))
        assertEquals(listOf(ServerMediaKind.MOVIE to listOf("90")), provider.searches)
    }

    @Test
    fun collectionCardsOpenTheirContentsAsACatalog() = runTest {
        val (catalog, _, connection) = catalog(
            libraries = listOf(FakeServerProvider.MOVIE_LIBRARY, FakeServerProvider.COLLECTION_LIBRARY)
        )
        assertTrue(catalog.titleLibraries().none { it.library.kind == ServerMediaKind.COLLECTION })
        assertTrue(catalog.searchAddons.first().single().catalogs.none { it.id == FakeServerProvider.COLLECTION_LIBRARY.id })

        val ref = ServerItemRef(connection.id, "c-popular")
        assertTrue(ServerCatalog.isCollection(ref.encode(), "collection"))
        assertFalse(ServerCatalog.isCollection(ref.encode(), "movie"))

        val row = catalog.collectionRow(ref)
        assertEquals("collection:c-popular", row.catalogId)
        assertEquals("Item c-popular", row.catalogName)
        assertEquals(listOf("movie", "series"), row.items.map { it.apiType })
        assertFalse(row.hasMore)
    }

    @Test
    fun catalogMetadataUsesCompatibleIdsOnly() = runTest {
        val provider = FakeServerProvider().apply {
            indexedIds["0"] = TrackingExternalIds(imdb = "tt0111161", tmdb = 278)
            indexedIds["1"] = TrackingExternalIds(tmdb = 550)
        }
        val (catalog, repository, connection) = catalog(provider)

        val native = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.take(3)
        assertTrue(native.all { ServerItemRef.isServerId(it.id) })

        repository.setCatalogMetadata(connection.id, true)
        val mapped = catalog.page(connection, FakeServerProvider.MOVIE_LIBRARY.id, skip = 0).items.take(3)
        assertEquals(listOf("tmdb:278", "tmdb:550", ServerItemRef(connection.id, "2").encode()), mapped.map { it.id })
        assertEquals("tt0111161", mapped.first().imdbId)
        assertEquals("Item 0", mapped.first().name)
    }

    @Test
    fun catalogIdFollowsInstalledMetaAddonsInOrder() {
        val title = ServerTitle(
            preview(ServerItemRef("c1", "7").encode(), ContentType.SERIES),
            TrackingExternalIds(imdb = "tt1", tmdb = 2, kitsu = 3)
        )
        val kitsu = metaAddon("kitsu", listOf("kitsu:"))
        val cinemeta = metaAddon("cinemeta", listOf("tt"))
        assertEquals("kitsu:3", title.catalogPreview(listOf(kitsu, cinemeta)).id)
        assertEquals("tt1", title.catalogPreview(listOf(cinemeta, kitsu)).id)
        assertEquals("tmdb:2", title.catalogPreview(listOf(metaAddon("mal", listOf("mal:")))).id)
        assertEquals(title.preview.id, title.copy(externalIds = TrackingExternalIds(kitsu = 3)).catalogPreview(emptyList()).id)
    }

    @Test
    fun collectionsKeepServerIdentity() {
        val collection = ServerTitle(
            preview(ServerItemRef("c1", "b1").encode(), ContentType.UNKNOWN, rawType = "collection"),
            TrackingExternalIds(imdb = "tt1")
        )
        assertEquals(collection.preview, collection.catalogPreview(listOf(metaAddon("any", emptyList()))))
    }

    @Test
    fun opensAnyServerRowByCatalogId() = runTest {
        val (catalog, _, connection) = catalog()

        val library = catalog.row(connection.id, FakeServerProvider.MOVIE_LIBRARY.id)
        assertEquals("Box · Movies", library.catalogName)
        assertEquals(ServerCatalog.PAGE_SIZE, library.items.size)
        assertTrue(library.hasMore)

        val resume = catalog.row(connection.id, "resume")
        assertEquals("Box · Continue watching", resume.catalogName)
        assertEquals(1, resume.items.size)

        val missing = runCatching { catalog.row(connection.id, "unknown") }.exceptionOrNull()
        assertEquals(ServerFailure.NOT_FOUND, missing?.serverFailure())
    }

    @Test
    fun loadsNativeDetails() = runTest {
        val (catalog, _, connection) = catalog()
        val details = catalog.details(ServerItemRef(connection.id, FakeServerProvider.SHOW_ID))
        assertEquals("series", details.meta.apiType)
        assertFalse(details.externalIds.hasAny)
        assertEquals(ServerItemRef(connection.id, FakeServerProvider.EPISODE_ID), ServerItemRef.parse(details.meta.videos.single().id))
    }

    @Test
    fun unknownConnectionsFailAsNotFound() = runTest {
        val (catalog, _, _) = catalog()
        val failure = runCatching {
            catalog.catalog(ServerCatalog.baseUrl("missing"), "server.missing", "", "10", "", "movie", 0, emptyMap())
        }.exceptionOrNull()
        assertEquals(ServerFailure.NOT_FOUND, failure?.serverFailure())
        assertNull(ServerCatalog.connectionId("https://addon.example"))
    }

    private fun preview(id: String, type: ContentType, rawType: String = type.toApiString()) = MetaPreview(
        id = id,
        type = type,
        rawType = rawType,
        name = "Show",
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = null,
        imdbRating = null,
        genres = emptyList()
    )

    private fun metaAddon(id: String, prefixes: List<String>) = Addon(
        id = id,
        name = id,
        version = "1",
        description = null,
        logo = null,
        baseUrl = "https://$id.example",
        catalogs = emptyList(),
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = listOf(AddonResource(name = "meta", types = listOf("movie", "series"), idPrefixes = prefixes)),
        idPrefixes = prefixes
    )
}
