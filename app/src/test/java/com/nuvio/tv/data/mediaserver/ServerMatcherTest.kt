package com.nuvio.tv.data.mediaserver

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.domain.repository.MetaRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerMatcherTest {
    private val tmdbService = mockk<TmdbService> {
        coEvery { ensureTmdbId(any(), any(), any()) } returns null
        coEvery { tmdbToImdb(any(), any()) } returns null
    }
    private val metaRepository = mockk<MetaRepository> {
        every { getCachedMeta(any(), any()) } returns null
    }

    private fun matcher(provider: FakeServerProvider = FakeServerProvider()): Triple<ServerMatcher, ServerRepository, ServerConnection> {
        val (repository, connection) = fakeServerRepository(provider)
        return Triple(ServerMatcher(repository, tmdbService, metaRepository), repository, connection)
    }

    @Test
    fun buildsRequestsFromCatalogIds() {
        val (matcher, _, _) = matcher()
        val movie = matcher.request("movie", "tt0111161", null, null)!!
        assertEquals(ServerMediaKind.MOVIE, movie.kind)
        assertEquals("tt0111161", movie.ids.imdb)

        val episode = matcher.request("series", "tmdb:1399:2:5", null, null)!!
        assertEquals(1399L, episode.ids.tmdb)
        assertEquals("tmdb:1399", episode.parentId)
        assertEquals(2, episode.season)
        assertEquals(5, episode.episode)
    }

    @Test
    fun acceptsEveryCatalogIdNamespace() {
        val (matcher, _, _) = matcher()
        assertEquals(123L, matcher.request("movie", "kitsu:123", null, null)!!.ids.kitsu)
        assertNull(matcher.request("series", "kitsu:123:5", null, null))
        val episode = matcher.request("series", "kitsu:123:5", 1, 5)!!
        assertEquals(1, episode.season)
        assertEquals(5, episode.episode)
        val index = LibraryIndex(listOf(ServerIndexEntry("a", TrackingExternalIds(kitsu = 123, anilist = 9))))
        assertEquals(listOf("a"), index.lookup(TrackingExternalIds(kitsu = 123)))
        assertTrue(index.lookup(TrackingExternalIds(kitsu = 123, anilist = 8)).isEmpty())
    }

    @Test
    fun skipsRequestsWithoutExactIds() {
        val (matcher, _, _) = matcher()
        assertNull(matcher.request("movie", "someaddon:123", null, null))
        assertNull(matcher.request("movie", ServerItemRef("c1", "x").encode(), null, null))
        assertNull(matcher.request("channel", "tt0111161", null, null))
    }

    @Test
    fun rejectsConflictingIdentifiersButKeepsDuplicateVersions() {
        val index = LibraryIndex(
            listOf(
                ServerIndexEntry("a", TrackingExternalIds(imdb = "tt1", tmdb = 10)),
                ServerIndexEntry("b", TrackingExternalIds(imdb = "tt1", tmdb = 99)),
                ServerIndexEntry("c", TrackingExternalIds(tmdb = 10)),
                ServerIndexEntry("d", TrackingExternalIds(imdb = "tt2"))
            )
        )
        assertEquals(listOf("a", "c"), index.lookup(TrackingExternalIds(imdb = "tt1", tmdb = 10)).sorted())
        assertTrue(index.lookup(TrackingExternalIds(imdb = "tt404")).isEmpty())
    }

    @Test
    fun rejectsEpisodesWithDifferentAirDates() {
        assertTrue(datesCompatible("2011-04-17", "2011-04-18T01:00:00.0000000Z"))
        assertTrue(datesCompatible(null, "2011-04-18"))
        assertFalse(datesCompatible("2011-04-17", "2011-05-01"))
    }

    @Test
    fun matchesMoviesByExactId() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161")
            indexedIds["43"] = TrackingExternalIds(imdb = "tt0068646")
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt0111161", null, null)!!

        assertTrue(matcher.supports(connection, ServerMediaKind.MOVIE))
        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
    }

    @Test
    fun matchesEpisodesThroughTheMatchedSeries() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds[FakeServerProvider.SHOW_ID] = TrackingExternalIds(imdb = "tt0944947")
            episodes[1 to 1] = ServerEpisode(FakeServerProvider.EPISODE_ID, "2011-04-17")
        }
        val (matcher, _, connection) = matcher(provider)

        val hit = matcher.request("series", "tt0944947:1:1", 1, 1)!!
        assertEquals(
            listOf(ServerItemRef(connection.id, FakeServerProvider.EPISODE_ID)),
            matcher.match(connection, hit, forceRefresh = false)
        )

        val miss = matcher.request("series", "tt0944947:1:2", 1, 2)!!
        assertTrue(matcher.match(connection, miss, forceRefresh = false).isEmpty())
    }

    @Test
    fun convertsTmdbIdsBeforeMatching() = runBlocking {
        val provider = FakeServerProvider().apply { indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161") }
        coEvery { tmdbService.tmdbToImdb(278, "movie") } returns "tt0111161"
        val (matcher, _, connection) = matcher(provider)

        val request = matcher.request("movie", "tmdb:278", null, null)!!

        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
    }

    @Test
    fun sharedLookupAddsServerSourcesForCatalogItems() = runBlocking {
        val provider = FakeServerProvider().apply { indexedIds["42"] = TrackingExternalIds(imdb = "tt0111161") }
        val (matcher, repository, connection) = matcher(provider)
        val streams = ServerStreams(repository, matcher)

        val found = streams.sources("movie", "tt0111161", null, null).single().load()
        assertEquals(ServerItemRef(connection.id, "42"), found.single().serverTarget?.item)

        assertTrue(streams.sources("movie", "tt0068646", null, null).single().load().isEmpty())
    }

    @Test
    fun findsATitleWithOneIdLookupInsteadOfTheIndex() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0078788")
            titleLookup = { library, query ->
                if (library.kind == ServerMediaKind.MOVIE && query.ids.imdb == "tt0078788") {
                    listOf(ServerIndexEntry("42", TrackingExternalIds(imdb = "tt0078788", tmdb = 28), year = 1979))
                } else {
                    emptyList()
                }
            }
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt0078788", null, null)!!

        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
        assertEquals("tt0078788", provider.titleQueries.single().ids.imdb)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun matchesEpisodesThroughASeriesFoundByLookup() = runBlocking {
        val provider = FakeServerProvider().apply {
            episodes[1 to 1] = ServerEpisode(FakeServerProvider.EPISODE_ID, "2011-04-17")
            titleLookup = { library, query ->
                if (library.kind == ServerMediaKind.SERIES) listOf(ServerIndexEntry(FakeServerProvider.SHOW_ID, query.ids)) else emptyList()
            }
        }
        val (matcher, _, connection) = matcher(provider)

        val request = matcher.request("series", "tt0944947:1:1", 1, 1)!!
        assertEquals(
            listOf(ServerItemRef(connection.id, FakeServerProvider.EPISODE_ID)),
            matcher.match(connection, request, forceRefresh = false)
        )
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun nameSearchOnlyMatchesItemsWithTheRequestedIds() = runBlocking {
        every { metaRepository.getCachedMeta("movie", "tt0078788") } returns
            meta("tt0078788", ServerMediaKind.MOVIE, "Apocalypse Now").copy(releaseInfo = "1979")
        val provider = FakeServerProvider().apply {
            titleLookup = { _, query ->
                query.name?.let {
                    listOf(
                        ServerIndexEntry("41", TrackingExternalIds(imdb = "tt9999999"), year = 1979),
                        ServerIndexEntry("42", TrackingExternalIds(imdb = "tt0078788"), year = 1979),
                        ServerIndexEntry("43", TrackingExternalIds(), year = 1979)
                    )
                }
            }
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt0078788", null, null)!!

        assertEquals("Apocalypse Now", request.name)
        assertEquals(1979, request.year)
        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
        assertEquals("Apocalypse Now", provider.titleQueries.single().name)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun aMissGivesTheOriginalTitleToTheSearchAndNeverBuildsTheIndex() = runBlocking {
        every { metaRepository.getCachedMeta("movie", "tmdb:77338") } returns
            meta("tmdb:77338", ServerMediaKind.MOVIE, "The Intouchables").copy(releaseInfo = "2011")
        val provider = FakeServerProvider().apply { titleLookup = { _, _ -> emptyList() } }
        val (repository, connection) = fakeServerRepository(provider)
        val matcher = ServerMatcher(repository, tmdbService, metaRepository) { tmdbId, kind ->
            "Intouchables".takeIf { tmdbId == 77338L && kind == ServerMediaKind.MOVIE }
        }
        val request = matcher.request("movie", "tmdb:77338", null, null)!!

        assertTrue(matcher.match(connection, request, forceRefresh = false).isEmpty())
        assertEquals("The Intouchables", provider.titleQueries.single().name)
        assertEquals("Intouchables", provider.titleQueries.single().originalName)
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun aSameNameTitleWithAnotherImdbIdIsNeverAMatch() = runBlocking {
        every { metaRepository.getCachedMeta("movie", "tt1160419") } returns
            meta("tt1160419", ServerMediaKind.MOVIE, "Dune").copy(releaseInfo = "2021")
        val provider = FakeServerProvider().apply {
            titleLookup = { _, _ -> listOf(ServerIndexEntry("84", TrackingExternalIds(imdb = "tt0087182", tmdb = 841), year = 1984)) }
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt1160419", null, null)!!

        assertTrue(matcher.match(connection, request, forceRefresh = false).isEmpty())
        assertEquals(0, provider.indexRequests)
    }

    @Test
    fun theYearOnlyGuardsMatchesWithoutTheImdbId() {
        val entries = listOf(
            ServerIndexEntry("a", TrackingExternalIds(tmdb = 28), year = 1979),
            ServerIndexEntry("b", TrackingExternalIds(tmdb = 28), year = 1985),
            ServerIndexEntry("c", TrackingExternalIds(imdb = "tt0078788", tmdb = 28), year = 2001),
            ServerIndexEntry("d", TrackingExternalIds(tmdb = 28), year = null),
            ServerIndexEntry("e", TrackingExternalIds(tmdb = 28), year = 1980)
        )
        val query = ServerTitleQuery(TrackingExternalIds(imdb = "tt0078788", tmdb = 28), "Apocalypse Now", 1979)

        assertEquals(listOf("a", "c", "d", "e"), verifiedTitleMatches(entries, query).sorted())
        assertEquals(listOf("a", "b", "c", "d", "e"), verifiedTitleMatches(entries, query.copy(year = null)).sorted())
    }

    @Test
    fun remembersFoundAndMissingTitles() = runBlocking {
        val provider = FakeServerProvider().apply {
            titleLookup = { _, query ->
                if (query.ids.imdb == "tt0078788") listOf(ServerIndexEntry("42", query.ids)) else emptyList()
            }
        }
        val (matcher, _, connection) = matcher(provider)
        val hit = matcher.request("movie", "tt0078788", null, null)!!
        val miss = matcher.request("movie", "tt0068646", null, null)!!

        repeat(2) { assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, hit, forceRefresh = false)) }
        repeat(2) { assertTrue(matcher.match(connection, miss, forceRefresh = false).isEmpty()) }
        assertEquals(2, provider.titleQueries.size)

        matcher.match(connection, hit, forceRefresh = true)
        assertEquals(3, provider.titleQueries.size)
    }

    @Test
    fun aSlowServerDoesNotHoldBackAnother() = runBlocking {
        val slow = FakeServerProvider(id = "slow").apply {
            titleLookup = { _, _ ->
                delay(20_000)
                emptyList()
            }
        }
        val quick = FakeServerProvider(id = "quick").apply {
            titleLookup = { _, query -> listOf(ServerIndexEntry("42", query.ids)) }
        }
        val repository = ServerRepository(MemoryServerPersistence(), listOf(slow, quick), CoroutineScope(Dispatchers.Unconfined))
        listOf("slow", "quick").forEach { providerId ->
            repository.store(
                ServerConnection(
                    id = "c$providerId",
                    providerId = providerId,
                    name = providerId,
                    address = "https://$providerId.example",
                    remoteServerId = "server-$providerId",
                    remoteUserId = "user-1",
                    userName = "viewer",
                    credentialRef = "k$providerId",
                    libraries = listOf(FakeServerProvider.MOVIE_LIBRARY)
                ),
                token = "token"
            )
        }
        val streams = ServerStreams(repository, ServerMatcher(repository, tmdbService, metaRepository))
        val sources = streams.sources("movie", "tt0078788", null, null)
        assertEquals(2, sources.size)

        val startedAt = System.currentTimeMillis()
        val loads = sources.map { source ->
            async(Dispatchers.IO) { runCatching { source.load() } to System.currentTimeMillis() - startedAt }
        }
        val (quickResult, quickMs) = loads[1].await()
        assertEquals(ServerItemRef("cquick", "42"), quickResult.getOrThrow().single().serverTarget?.item)
        assertTrue("quick server took $quickMs ms", quickMs < 3_000)

        val (slowResult, slowMs) = loads[0].await()
        assertEquals(ServerFailure.INCOMPLETE, (slowResult.exceptionOrNull() as ServerException).failure)
        assertTrue("slow server gave up after $slowMs ms", slowMs < 10_000)
        assertEquals(0, slow.indexRequests + quick.indexRequests)
    }

    @Test
    fun fallsBackToTheIndexWhenTheServerCannotLookUpTheTitle() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0078788")
            titleLookup = { _, query -> query.name?.let { emptyList() } }
        }
        val (matcher, _, connection) = matcher(provider)
        val request = matcher.request("movie", "tt0078788", null, null)!!

        assertNull(request.name)
        assertEquals(listOf(ServerItemRef(connection.id, "42")), matcher.match(connection, request, forceRefresh = false))
        assertEquals(1, provider.titleQueries.size)
        assertTrue(provider.indexRequests > 0)

        val plain = FakeServerProvider().apply { indexedIds["7"] = TrackingExternalIds(imdb = "tt0068646") }
        val (plainMatcher, _, plainConnection) = matcher(plain)
        val plainRequest = plainMatcher.request("movie", "tt0068646", null, null)!!
        assertEquals(listOf(ServerItemRef(plainConnection.id, "7")), plainMatcher.match(plainConnection, plainRequest, forceRefresh = false))
        assertTrue(plain.indexRequests > 0)
    }

    @Test
    fun noIndexIsBuiltBeforeATitleNeedsOne() = runBlocking {
        val provider = FakeServerProvider().apply {
            indexedIds["42"] = TrackingExternalIds(imdb = "tt0078788")
            titleLookup = { _, query -> listOf(ServerIndexEntry("42", query.ids)) }
        }
        val (matcher, repository, connection) = matcher(provider)
        val streams = ServerStreams(repository, matcher)

        assertTrue(streams.canServe("movie", "tt0078788"))
        repository.setLibrarySelected(connection.id, FakeServerProvider.SERIES_LIBRARY.id, selected = false)
        repository.setLibrarySelected(connection.id, FakeServerProvider.SERIES_LIBRARY.id, selected = true)
        assertEquals(0, provider.indexRequests)

        assertEquals(1, streams.sources("movie", "tt0078788", null, null).single().load().size)
        assertEquals(0, provider.indexRequests)
    }
}
