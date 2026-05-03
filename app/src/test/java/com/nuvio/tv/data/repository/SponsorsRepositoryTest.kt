package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.SponsorsApi
import com.nuvio.tv.data.remote.dto.SponsorDto
import com.nuvio.tv.data.remote.dto.SponsorsResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SponsorsRepositoryTest {

    @Test
    fun `sorts sponsors by newest first and drops invalid rows`() = runTest {
        val repository = SponsorsRepository(
            sponsorsApi = FakeSponsorsApi(
                response = Response.success(
                    SponsorsResponseDto(
                        sponsors = listOf(
                            sponsor(name = "Older", createdAt = "2026-01-01T00:00:00Z"),
                            sponsor(name = "Newest", createdAt = "2026-04-28T10:02:59.464Z"),
                            sponsor(name = " ", createdAt = "2026-04-28T10:02:50.796Z")
                        )
                    )
                )
            )
        )

        val result = repository.getSponsors()

        assertTrue(result.isSuccess)
        val sponsors = result.getOrThrow()
        assertEquals(listOf("Newest", "Older"), sponsors.map { it.name })
        assertEquals("https://example.com/Newest", sponsors.first().channelUrl)
    }

    @Test
    fun `returns failure on api error`() = runTest {
        val repository = SponsorsRepository(
            sponsorsApi = FakeSponsorsApi(
                response = Response.error(
                    500,
                    "{}".toResponseBody("application/json".toMediaType())
                )
            )
        )

        val result = repository.getSponsors()

        assertTrue(result.isFailure)
    }

    private fun sponsor(
        name: String,
        createdAt: String,
        channelUrl: String? = "https://example.com/$name"
    ) = SponsorDto(
        id = name,
        name = name,
        channelUrl = channelUrl,
        createdAt = createdAt,
        updatedAt = createdAt
    )

    private class FakeSponsorsApi(
        private val response: Response<SponsorsResponseDto>
    ) : SponsorsApi {
        override suspend fun getSponsors(): Response<SponsorsResponseDto> = response
    }
}
