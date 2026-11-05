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

    private fun service(traktApi: TraktApi, addonSeasons: List<Int>): TraktEpisodeMappingService {
        val authService = mockk<TraktAuthService> {
            coEvery { getCurrentAuthState() } returns TraktAuthState(accessToken = "a", refreshToken = "r")
            coEvery { executeAuthorizedRequest<List<TraktSeasonDto>>(any()) } coAnswers {
                firstArg<suspend (String) -> Response<List<TraktSeasonDto>>>().invoke("Bearer a")
            }
        }
        val addonEpisodeListService = mockk<AddonEpisodeListService> {
            coEvery { episodes(any(), any()) } returns addonSeasons.flatMapIndexed { index, count ->
                (1..count).map { episode -> EpisodeMappingEntry(season = index + 1, episode = episode) }
            }
        }
        return TraktEpisodeMappingService(traktApi, authService, addonEpisodeListService)
    }

    private fun traktApi(traktSeasons: List<Int>) = mockk<TraktApi> {
        coEvery { getShowSeasons(any(), any(), any()) } returns Response.success(
            traktSeasons.mapIndexed { index, count ->
                TraktSeasonDto(
                    number = index + 1,
                    episodes = (1..count).map { episode -> TraktEpisodeDto(season = index + 1, number = episode) }
                )
            }
        )
    }
}
