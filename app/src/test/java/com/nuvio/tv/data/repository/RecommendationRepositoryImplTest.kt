package com.nuvio.tv.data.repository

import android.util.Log
import com.nuvio.tv.core.network.NetworkResult
import com.nuvio.tv.domain.model.CatalogDescriptor
import com.nuvio.tv.domain.model.CatalogExtra
import com.nuvio.tv.domain.model.CatalogRow
import com.nuvio.tv.domain.model.ContentType
import com.nuvio.tv.domain.model.Addon
import com.nuvio.tv.domain.model.AddonResource
import com.nuvio.tv.domain.model.MetaPreview
import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.WatchProgress
import com.nuvio.tv.domain.repository.AddonRepository
import com.nuvio.tv.domain.repository.CatalogRepository
import com.nuvio.tv.domain.repository.WatchProgressRepository
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class RecommendationRepositoryImplTest {

    private lateinit var watchProgressRepository: WatchProgressRepository
    private lateinit var addonRepository: AddonRepository
    private lateinit var catalogRepository: CatalogRepository
    private lateinit var repository: RecommendationRepositoryImpl

    private fun metaPreview(
        id: String,
        name: String = "Title $id",
        genres: List<String> = emptyList(),
        imdbRating: Float? = null,
        releaseInfo: String? = null,
        type: ContentType = ContentType.MOVIE
    ) = MetaPreview(
        id = id,
        type = type,
        name = name,
        poster = null,
        posterShape = PosterShape.POSTER,
        background = null,
        logo = null,
        description = null,
        releaseInfo = releaseInfo,
        imdbRating = imdbRating,
        genres = genres
    )

    private fun watchProgress(contentId: String, contentType: String = "movie") = WatchProgress(
        contentId = contentId,
        contentType = contentType,
        name = "Title $contentId",
        poster = null,
        backdrop = null,
        logo = null,
        videoId = contentId,
        season = null,
        episode = null,
        episodeTitle = null,
        position = 1000L,
        duration = 5000L,
        lastWatched = System.currentTimeMillis(),
        addonBaseUrl = null,
        progressPercent = null,
        traktPlaybackId = null,
        traktMovieId = null,
        traktShowId = null,
        traktEpisodeId = null
    )

    private fun addon(
        id: String = "test-addon",
        baseUrl: String = "https://addon.example.com",
        catalogs: List<CatalogDescriptor> = emptyList()
    ) = Addon(
        id = id,
        name = "Test Addon",
        version = "1.0",
        description = null,
        logo = null,
        baseUrl = baseUrl,
        catalogs = catalogs,
        types = listOf(ContentType.MOVIE, ContentType.SERIES),
        resources = listOf(AddonResource("catalog", listOf("movie", "series"), null))
    )

    private fun catalogDescriptor(
        id: String = "top",
        name: String = "Top",
        type: ContentType = ContentType.MOVIE,
        hasGenreFilter: Boolean = false,
        genres: List<String> = emptyList()
    ) = CatalogDescriptor(
        type = type,
        id = id,
        name = name,
        extra = if (hasGenreFilter) {
            listOf(CatalogExtra(name = "genre", options = genres.ifEmpty { null }))
        } else {
            emptyList()
        }
    )

    private fun catalogRow(
        items: List<MetaPreview>,
        addonId: String = "test-addon",
        catalogId: String = "top",
        type: ContentType = ContentType.MOVIE
    ) = CatalogRow(
        addonId = addonId,
        addonName = "Test Addon",
        addonBaseUrl = "https://addon.example.com",
        catalogId = catalogId,
        catalogName = "Top",
        type = type,
        items = items
    )

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>()) } returns 0
        every { Log.w(any<String>(), any<String>(), any<Throwable>()) } returns 0

        watchProgressRepository = mockk()
        addonRepository = mockk()
        catalogRepository = mockk()
        repository = RecommendationRepositoryImpl(
            watchProgressRepository = watchProgressRepository,
            addonRepository = addonRepository,
            catalogRepository = catalogRepository
        )
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `getSurpriseRecommendation with no addons returns null`() = runTest {
        every { addonRepository.getInstalledAddons() } returns flowOf(emptyList())
        every { watchProgressRepository.allProgress } returns flowOf(emptyList())

        val result = repository.getSurpriseRecommendation()
        assertNull(result)
    }

    @Test
    fun `getSurpriseRecommendation with empty history returns fallback item`() = runTest {
        val items = listOf(
            metaPreview("tt1", genres = listOf("Action")),
            metaPreview("tt2", genres = listOf("Drama"))
        )
        val catalog = catalogDescriptor(id = "top")
        val testAddon = addon(catalogs = listOf(catalog))

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(testAddon))
        every { watchProgressRepository.allProgress } returns flowOf(emptyList())
        every {
            catalogRepository.getCatalog(
                addonBaseUrl = any(),
                addonId = any(),
                addonName = any(),
                catalogId = any(),
                catalogName = any(),
                type = any(),
                skip = any(),
                skipStep = any(),
                extraArgs = any(),
                supportsSkip = any()
            )
        } returns flowOf(NetworkResult.Success(catalogRow(items)))

        val result = repository.getSurpriseRecommendation()
        assertNotNull(result)
        assertTrue(result!!.id in listOf("tt1", "tt2"))
    }

    @Test
    fun `getSurpriseRecommendation excludes already watched items`() = runTest {
        val watched = listOf(watchProgress("tt1"))
        val catalogItems = listOf(
            metaPreview("tt1", genres = listOf("Action")),
            metaPreview("tt2", genres = listOf("Action")),
            metaPreview("tt3", genres = listOf("Drama"))
        )
        val catalog = catalogDescriptor(id = "top")
        val testAddon = addon(catalogs = listOf(catalog))

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(testAddon))
        every { watchProgressRepository.allProgress } returns flowOf(watched)
        every {
            catalogRepository.getCatalog(
                addonBaseUrl = any(),
                addonId = any(),
                addonName = any(),
                catalogId = any(),
                catalogName = any(),
                type = any(),
                skip = any(),
                skipStep = any(),
                extraArgs = any(),
                supportsSkip = any()
            )
        } returns flowOf(NetworkResult.Success(catalogRow(catalogItems)))

        val result = repository.getSurpriseRecommendation()
        assertNotNull(result)
        // tt1 was watched, so it should not be recommended
        assertTrue(result!!.id != "tt1")
    }

    @Test
    fun `selectWeightedRandom returns genre matched item with higher probability`() {
        val candidates = listOf(
            metaPreview("tt1", genres = listOf("Action", "Drama"), imdbRating = 8.0f),
            metaPreview("tt2", genres = listOf("Comedy"), imdbRating = 5.0f),
            metaPreview("tt3", genres = listOf("Horror"), imdbRating = 3.0f)
        )
        val topGenres = listOf("Action", "Drama")

        // Run multiple times and verify that genre-matched items are selected more often
        val results = mutableMapOf<String, Int>()
        repeat(100) {
            val result = repository.selectWeightedRandom(candidates, topGenres)
            assertNotNull(result)
            results[result!!.id] = (results[result.id] ?: 0) + 1
        }

        // tt1 has 2 genre matches + high rating, should be selected most often
        val tt1Count = results["tt1"] ?: 0
        val tt3Count = results["tt3"] ?: 0
        assertTrue("tt1 ($tt1Count) should be selected more than tt3 ($tt3Count)", tt1Count > tt3Count)
    }

    @Test
    fun `selectWeightedRandom with empty candidates returns null`() {
        val result = repository.selectWeightedRandom(emptyList(), listOf("Action"))
        assertNull(result)
    }

    @Test
    fun `getSurpriseRecommendation with watch history uses genre based selection`() = runTest {
        val watched = listOf(
            watchProgress("tt1"),
            watchProgress("tt2"),
            watchProgress("tt3")
        )
        // Catalog items include watched items (with genre data) and unwatched items
        val catalogItems = listOf(
            metaPreview("tt1", genres = listOf("Action", "Thriller")),
            metaPreview("tt2", genres = listOf("Action")),
            metaPreview("tt3", genres = listOf("Action", "Drama")),
            metaPreview("tt4", genres = listOf("Action", "Thriller"), imdbRating = 7.5f),
            metaPreview("tt5", genres = listOf("Romance"), imdbRating = 6.0f)
        )
        val genreCatalogItems = listOf(
            metaPreview("tt4", genres = listOf("Action", "Thriller"), imdbRating = 7.5f),
            metaPreview("tt6", genres = listOf("Action"), imdbRating = 8.0f)
        )
        val catalog = catalogDescriptor(id = "top")
        val genreCatalog = catalogDescriptor(
            id = "genre-catalog",
            hasGenreFilter = true,
            genres = listOf("Action", "Drama", "Thriller")
        )
        val testAddon = addon(catalogs = listOf(catalog, genreCatalog))

        every { addonRepository.getInstalledAddons() } returns flowOf(listOf(testAddon))
        every { watchProgressRepository.allProgress } returns flowOf(watched)
        every {
            catalogRepository.getCatalog(
                addonBaseUrl = any(),
                addonId = any(),
                addonName = any(),
                catalogId = eq("top"),
                catalogName = any(),
                type = any(),
                skip = any(),
                skipStep = any(),
                extraArgs = any(),
                supportsSkip = any()
            )
        } returns flowOf(NetworkResult.Success(catalogRow(catalogItems)))
        every {
            catalogRepository.getCatalog(
                addonBaseUrl = any(),
                addonId = any(),
                addonName = any(),
                catalogId = eq("genre-catalog"),
                catalogName = any(),
                type = any(),
                skip = any(),
                skipStep = any(),
                extraArgs = any(),
                supportsSkip = any()
            )
        } returns flowOf(NetworkResult.Success(catalogRow(genreCatalogItems, catalogId = "genre-catalog")))

        val result = repository.getSurpriseRecommendation()
        assertNotNull(result)
        // Should not recommend already watched items
        assertTrue(result!!.id !in listOf("tt1", "tt2", "tt3"))
    }
}
