package com.nuvio.tv.data.repository

import com.nuvio.tv.data.local.TraktAuthState
import com.nuvio.tv.data.remote.api.TraktApi
import com.nuvio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nuvio.tv.data.remote.dto.trakt.TraktSeasonDto
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class TraktEpisodeMappingServiceTest {
    @Test
    fun `episodes the addon has keep their numbers when another season's count differs`() = runTest {
        // The Daily Show: Trakt and Cinemeta disagree on season 1's episode count, which used
        // to shift every later episode through the whole-show index fallback.
        val traktApi = traktApi(traktSeasons = listOf(61, 160))
        val service = service(traktApi, addonSeasons = listOf(65, 160))

        assertNull(service.resolveAddonEpisodeMapping("tt0115147", "series", season = 2, episode = 1))
        coVerify(exactly = 0) { traktApi.getShowSeasons(any(), any(), any()) }
    }

    @Test
    fun `episodes missing from the addon are still remapped`() = runTest {
        val service = service(traktApi(traktSeasons = listOf(48)), addonSeasons = listOf(24, 24))

        val mapped = service.resolveAddonEpisodeMapping("tt1", "series", season = 1, episode = 35)

        assertEquals(2, mapped?.season)
        assertEquals(11, mapped?.episode)
    }

    @Test
    fun `written episodes keep their numbers when Trakt has them and an earlier season's count differs`() = runTest {
        // The Block: Trakt lists two more episodes than the addon before the current season and
        // room-week titles repeat every season, so the whole-show index used to mark the
        // episode two places earlier on Trakt (S2E15 -> S2E13 here).
        val roomWeek = { _: Int, episode: Int -> "Room Week ($episode)" }
        val service = service(
            traktApi(traktSeasons = listOf(15, 50), title = roomWeek),
            addonSeasons = listOf(13, 50),
            addonTitle = roomWeek
        )

        val mapped = service.resolveEpisodeMapping("tt0418372", "series", videoId = null, season = 2, episode = 15)

        assertEquals(2, mapped?.season)
        assertEquals(15, mapped?.episode)
    }

    @Test
    fun `written episodes Trakt lacks are still remapped`() = runTest {
        val service = service(traktApi(traktSeasons = listOf(48)), addonSeasons = listOf(24, 24))

        val mapped = service.resolveEpisodeMapping("tt1", "series", videoId = null, season = 2, episode = 11)

        assertEquals(1, mapped?.season)
        assertEquals(35, mapped?.episode)
    }

    @Test
    fun `a unique title match still wins over the same number when writing`() = runTest {
        // Seasons split differently: the addon's S2E3 is Trakt's S2E1, and Trakt's S2E3 is a
        // different episode.
        val service = service(
            traktApi(traktSeasons = listOf(7, 3), title = chapterTitles(listOf(7, 3))),
            addonSeasons = listOf(5, 5),
            addonTitle = chapterTitles(listOf(5, 5))
        )

        val mapped = service.resolveEpisodeMapping("tt2", "series", videoId = null, season = 2, episode = 3)

        assertEquals(2, mapped?.season)
        assertEquals(1, mapped?.episode)
    }

    private fun chapterTitles(seasonCounts: List<Int>): (Int, Int) -> String = { season, episode ->
        "Chapter ${seasonCounts.take(season - 1).sum() + episode}"
    }

    private fun service(
        traktApi: TraktApi,
        addonSeasons: List<Int>,
        addonTitle: (season: Int, episode: Int) -> String? = { _, _ -> null }
    ): TraktEpisodeMappingService {
        val authService = mockk<TraktAuthService> {
            coEvery { getCurrentAuthState() } returns TraktAuthState(accessToken = "a", refreshToken = "r")
            coEvery { executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("Bearer a")
            }
        }
        val addonEpisodeListService = mockk<AddonEpisodeListService> {
            coEvery { episodes(any(), any()) } returns addonSeasons.flatMapIndexed { index, count ->
                (1..count).map { episode ->
                    EpisodeMappingEntry(season = index + 1, episode = episode, title = addonTitle(index + 1, episode))
                }
            }
        }
        return TraktEpisodeMappingService(traktApi, authService, addonEpisodeListService)
    }

    private fun traktApi(
        traktSeasons: List<Int>,
        title: (season: Int, episode: Int) -> String? = { _, _ -> null }
    ) = mockk<TraktApi> {
        coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(
            traktSeasons.mapIndexed { index, count ->
                TraktSeasonDto(
                    number = index + 1,
                    episodes = (1..count).map { episode ->
                        TraktEpisodeDto(title = title(index + 1, episode), season = index + 1, number = episode)
                    }
                )
            }
        )
    }
}
