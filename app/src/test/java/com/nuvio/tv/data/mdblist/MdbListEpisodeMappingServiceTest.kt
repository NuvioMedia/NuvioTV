package com.nuvio.tv.data.mdblist

import com.nuvio.tv.core.tmdb.TmdbService
import com.nuvio.tv.core.tracking.TrackingCatalogReference
import com.nuvio.tv.core.tracking.TrackingEpisode
import com.nuvio.tv.core.tracking.TrackingExternalIds
import com.nuvio.tv.core.tracking.TrackingMediaKind
import com.nuvio.tv.core.tracking.TrackingMediaReference
import com.nuvio.tv.data.remote.api.TmdbApi
import com.nuvio.tv.data.remote.api.TmdbDetailsResponse
import com.nuvio.tv.data.remote.api.TmdbEpisode
import com.nuvio.tv.data.remote.api.TmdbSeasonResponse
import com.nuvio.tv.data.remote.api.TmdbSeasonSummary
import com.nuvio.tv.data.repository.AddonEpisodeListService
import com.nuvio.tv.data.repository.EpisodeMappingEntry
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

class MdbListEpisodeMappingServiceTest {
    @Test
    fun `episodes move between one TMDB season and the addon's two seasons`() {
        val mapping = splitAnimeMapping()
        assertEquals(EpisodeMappingEntry(2, 11, videoId = "tt1:2:11"), mapping.toAddon(1, 35))
        assertEquals(EpisodeMappingEntry(1, 35), mapping.toTmdb(2, 11, "tt1:2:11"))
        assertEquals(EpisodeMappingEntry(1, 24), mapping.toTmdb(1, 24))
        assertNull(MdbListEpisodeMapping.Unchanged.toAddon(1, 35))
        assertNull(MdbListEpisodeMapping.Unchanged.toTmdb(2, 11))
    }

    @Test
    fun `addon episodes are written to MDBList in TMDB numbering`() = runTest {
        val service = service(addonSeasons = listOf(24, 24), tmdbSeasons = listOf(48))
        val mapped = service.toMdbListNumbering(episodeReference(2, 11), mediaIndex = null)
        assertEquals(1, mapped.episode?.season)
        assertEquals(35, mapped.episode?.number)
        assertTrue(service.cached("tt1")!!.remaps)
    }

    @Test
    fun `shows split the same way keep their numbers without loading TMDB seasons`() = runTest {
        val tmdbApi = tmdbApi(listOf(24, 24))
        val service = service(addonSeasons = listOf(24, 24), tmdbApi = tmdbApi)
        val reference = episodeReference(2, 11)
        assertSame(reference, service.toMdbListNumbering(reference, mediaIndex = null))
        assertFalse(service.cached("tt1")!!.remaps)
        coVerify(exactly = 0) { tmdbApi.getTvSeasonDetails(any(), any(), any(), any()) }
    }

    @Test
    fun `shows with the same seasons but merged two-parters keep their numbers`() = runTest {
        val tmdbApi = tmdbApi(listOf(23, 24))
        val service = service(addonSeasons = listOf(24, 24), tmdbApi = tmdbApi)
        val reference = episodeReference(2, 1)
        assertSame(reference, service.toMdbListNumbering(reference, mediaIndex = null))
        assertFalse(service.cached("tt1")!!.remaps)
        coVerify(exactly = 0) { tmdbApi.getTvSeasonDetails(any(), any(), any(), any()) }
    }

    @Test
    fun `missing addon episodes leave the mapping unresolved`() = runTest {
        val service = service(addonSeasons = emptyList(), tmdbSeasons = listOf(48))
        assertNull(service.resolve("tt1", 1))
        assertNull(service.cached("tt1"))
    }

    private fun episodeReference(season: Int, episode: Int) = TrackingMediaReference(
        kind = TrackingMediaKind.SHOW,
        ids = TrackingExternalIds(imdb = "tt1", tmdb = 1),
        episode = TrackingEpisode(season = season, number = episode),
        catalog = TrackingCatalogReference("tt1", "series", "tt1:$season:$episode")
    )

    private fun service(
        addonSeasons: List<Int>,
        tmdbSeasons: List<Int> = emptyList(),
        tmdbApi: TmdbApi = tmdbApi(tmdbSeasons)
    ): MdbListEpisodeMappingService {
        val addon = mockk<AddonEpisodeListService> {
            coEvery { episodes(any(), any()) } returns addonSeasons.flatMapIndexed { index, count ->
                (1..count).map { EpisodeMappingEntry(index + 1, it, videoId = "tt1:${index + 1}:$it") }
            }
        }
        return MdbListEpisodeMappingService(addon, tmdbApi, mockk<TmdbService>())
    }

    private fun tmdbApi(seasons: List<Int>) = mockk<TmdbApi> {
        coEvery { getTvDetails(1, any(), any()) } returns Response.success(
            TmdbDetailsResponse(id = 1, seasons = seasons.mapIndexed { index, count -> TmdbSeasonSummary(index + 1, count) })
        )
        seasons.forEachIndexed { index, count ->
            coEvery { getTvSeasonDetails(1, index + 1, any(), any()) } returns Response.success(
                TmdbSeasonResponse(index + 1, (1..count).map { TmdbEpisode(episodeNumber = it) })
            )
        }
    }
}
