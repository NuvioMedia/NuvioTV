package com.nuvio.tv.core.trakt

import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.data.local.AuthSessionNoticeDataStore
import com.nuvio.tv.data.local.TraktAuthDataStore
import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListIdsDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListImagesDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListItemDto
import com.nuvio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nuvio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nuvio.tv.data.remote.dto.trakt.TraktProminentListDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSearchResultDto
import com.nuvio.tv.data.remote.dto.trakt.TraktShowDto
import com.nuvio.tv.data.remote.dto.trakt.TraktUserDto
import com.nuvio.tv.data.repository.TraktAuthService
import com.nuvio.tv.domain.model.TmdbCollectionMediaType
import com.nuvio.tv.domain.model.TraktCollectionSource
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import okhttp3.Headers.Companion.headersOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class TraktPublicListSourceResolverTest {

    @Test
    fun `parseTraktListId accepts numeric ids and trakt urls`() {
        val resolver = resolver(mockk(relaxed = true), authenticated = true)

        assertEquals(123L, resolver.parseTraktListId("123"))
        assertEquals(456L, resolver.parseTraktListId("https://trakt.tv/lists/456"))
        assertEquals(null, resolver.parseTraktListId("https://trakt.tv/users/nuvio/lists/weekend"))
    }

    @Test
    fun `list import metadata resolves slug url to numeric list id`() = runTest {
        val api = mockk<TraktApi>()
        coEvery { api.getPublicList(null, "weekend", "full,images") } returns Response.success(
            TraktListSummaryDto(
                name = "Weekend",
                ids = TraktListIdsDto(trakt = 88L, slug = "weekend"),
                images = TraktListImagesDto(posters = listOf("media.trakt.tv/images/lists/000/000/088/posters/medium/poster.jpg.webp"))
            )
        )
        val resolver = resolver(api, authenticated = true)

        val metadata = resolver.listImportMetadata("https://trakt.tv/users/nuvio/lists/weekend")

        assertEquals("Weekend", metadata.title)
        assertEquals(88L, metadata.traktListId)
        assertEquals("https://media.trakt.tv/images/lists/000/000/088/posters/medium/poster.jpg.webp", metadata.coverImageUrl)
    }

    @Test
    fun `movie list source maps items and pagination`() = runTest {
        val api = mockk<TraktApi>()
        coEvery {
            api.getPublicListItems(
                null,
                "77",
                "movie",
                "full,images",
                2,
                50,
                "rank",
                "asc"
            )
        } returns Response.success(
            listOf(
                TraktListItemDto(
                    rank = 1,
                    type = "movie",
                    movie = TraktMovieDto(
                        title = "Movie",
                        year = 2024,
                        ids = TraktIdsDto(trakt = 9, imdb = "tt1234567", tmdb = 90),
                        overview = "Overview",
                        released = "2024-01-01",
                        rating = 7.8,
                        images = TraktImagesDto(
                            poster = listOf("media.trakt.tv/images/movies/000/000/009/posters/medium/poster.jpg.webp"),
                            fanart = listOf("media.trakt.tv/images/movies/000/000/009/fanarts/medium/fanart.jpg.webp"),
                            logo = listOf("media.trakt.tv/images/movies/000/000/009/logos/medium/logo.png.webp")
                        )
                    )
                )
            ),
            headersOf("X-Pagination-Page-Count", "3")
        )
        val resolver = resolver(api, authenticated = true)
        val result = resolver.resolve(
            TraktCollectionSource(
                title = "Public",
                traktListId = 77L,
                mediaType = TmdbCollectionMediaType.MOVIE
            ),
            page = 2
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("trakt_list_77_movie_rank_asc", result.data.catalogId)
        assertEquals("Public", result.data.catalogName)
        assertEquals(2, result.data.currentPage)
        assertTrue(result.data.hasMore)
        assertEquals("tt1234567", result.data.items.single().id)
        assertEquals("movie", result.data.items.single().apiType)
        assertEquals("https://media.trakt.tv/images/movies/000/000/009/posters/medium/poster.jpg.webp", result.data.items.single().poster)
        assertEquals("https://media.trakt.tv/images/movies/000/000/009/fanarts/medium/fanart.jpg.webp", result.data.items.single().background)
        assertEquals("https://media.trakt.tv/images/movies/000/000/009/logos/medium/logo.png.webp", result.data.items.single().logo)
    }

    @Test
    fun `show list source maps items and latest query params`() = runTest {
        val api = mockk<TraktApi>()
        coEvery {
            api.getPublicListItems(
                null,
                "77",
                "show",
                "full,images",
                1,
                50,
                "popularity",
                "desc"
            )
        } returns Response.success(
            listOf(
                TraktListItemDto(
                    rank = 1,
                    type = "show",
                    show = TraktShowDto(
                        title = "Series",
                        year = 2023,
                        ids = TraktIdsDto(trakt = 8, tmdb = 70),
                        overview = "Show overview",
                        firstAired = "2023-02-01",
                        rating = 8.2,
                        images = TraktImagesDto(
                            poster = listOf("//media.trakt.tv/images/shows/000/000/008/posters/medium/poster.jpg.webp"),
                            fanart = listOf("//media.trakt.tv/images/shows/000/000/008/fanarts/medium/fanart.jpg.webp")
                        )
                    )
                )
            ),
            headersOf("X-Pagination-Page-Count", "1")
        )
        val resolver = resolver(api, authenticated = true)
        val result = resolver.resolve(
            TraktCollectionSource(
                title = "Public Shows",
                traktListId = 77L,
                mediaType = TmdbCollectionMediaType.TV,
                sortBy = "popularity",
                sortHow = "desc"
            )
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("trakt_list_77_tv_popularity_desc", result.data.catalogId)
        assertEquals("series", result.data.rawType)
        assertEquals("tmdb:70", result.data.items.single().id)
        assertEquals("series", result.data.items.single().apiType)
        assertEquals("https://media.trakt.tv/images/shows/000/000/008/posters/medium/poster.jpg.webp", result.data.items.single().poster)
    }

    @Test
    fun `search maps public list metadata`() = runTest {
        val api = mockk<TraktApi>()
        coEvery { api.searchLists(null, "award", "full,images", 1, 20) } returns Response.success(
            listOf(
                TraktSearchResultDto(
                    type = "list",
                    list = TraktListSummaryDto(
                        name = "Award Winners",
                        itemCount = 42,
                        likes = 7,
                        commentCount = 3,
                        ids = TraktListIdsDto(trakt = 12L, slug = "award-winners"),
                        user = TraktUserDto(username = "nuvio")
                    )
                )
            )
        )
        val resolver = resolver(api, authenticated = true)

        val result = resolver.searchPublicLists("award").single()

        assertEquals(12L, result.traktListId)
        assertEquals("Award Winners", result.title)
        assertTrue(result.subtitle.contains("nuvio"))
        assertTrue(result.subtitle.contains("42 items"))
    }

    @Test
    fun `trending and popular map public list metadata`() = runTest {
        val api = mockk<TraktApi>()
        coEvery { api.getTrendingLists(null, "full,images", 1, 20) } returns Response.success(
            listOf(
                TraktProminentListDto(
                    likeCount = 10,
                    commentCount = 4,
                    list = TraktListSummaryDto(
                        name = "Trending",
                        itemCount = 25,
                        ids = TraktListIdsDto(trakt = 21L, slug = "trending"),
                        user = TraktUserDto(username = "curator"),
                        images = TraktListImagesDto(posters = listOf("media.trakt.tv/images/lists/000/000/021/posters/medium/trending.jpg.webp"))
                    )
                )
            )
        )
        coEvery { api.getPopularLists(null, "full,images", 1, 20) } returns Response.success(
            listOf(
                TraktProminentListDto(
                    likeCount = 30,
                    commentCount = 6,
                    list = TraktListSummaryDto(
                        name = "Popular",
                        itemCount = 80,
                        ids = TraktListIdsDto(trakt = 22L, slug = "popular"),
                        user = TraktUserDto(username = "editor")
                    )
                )
            )
        )
        val resolver = resolver(api, authenticated = true)

        val trending = resolver.trendingPublicLists().single()
        val popular = resolver.popularPublicLists().single()

        assertEquals(21L, trending.traktListId)
        assertEquals("Trending", trending.title)
        assertTrue(trending.subtitle.contains("25 items"))
        assertTrue(trending.subtitle.contains("10 likes"))
        assertFalse(trending.subtitle.contains("comments"))
        assertEquals("https://media.trakt.tv/images/lists/000/000/021/posters/medium/trending.jpg.webp", trending.coverImageUrl)
        assertEquals(22L, popular.traktListId)
        assertEquals("Popular", popular.title)
        assertTrue(popular.subtitle.contains("30 likes"))
    }

    @Test
    fun `unauthenticated resolve loads public list sources`() = runTest {
        val api = mockk<TraktApi>()
        coEvery {
            api.getPublicListItems(
                null,
                "1",
                "movie",
                "full,images",
                1,
                50,
                "rank",
                "asc"
            )
        } returns Response.success(emptyList(), headersOf("X-Pagination-Page-Count", "1"))
        val resolver = resolver(api, authenticated = false)

        val result = resolver.resolve(
            TraktCollectionSource(title = "Public", traktListId = 1L)
        ).first { it is NetworkResult.Success } as NetworkResult.Success

        assertEquals("Public", result.data.catalogName)
        assertEquals(0, result.data.items.size)
    }

    private fun resolver(api: TraktApi, authenticated: Boolean): TraktPublicListSourceResolver {
        val authStore = mockk<TraktAuthDataStore> {
            every { isAuthenticated } returns flowOf(authenticated)
            every { state } returns flowOf(
                TraktAuthState(
                    accessToken = "token",
                    refreshToken = "refresh",
                    createdAt = System.currentTimeMillis() / 1000L,
                    expiresIn = 3600
                )
            )
        }
        val authService = TraktAuthService(
            traktApi = api,
            traktAuthDataStore = authStore,
            authSessionNoticeDataStore = mockk<AuthSessionNoticeDataStore>(relaxed = true)
        )
        return TraktPublicListSourceResolver(
            traktApi = api,
            traktAuthService = authService
        )
    }
}
