package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.DonationsApi
import com.nuvio.tv.data.remote.dto.DonationDto
import com.nuvio.tv.data.remote.dto.DonationsResponseDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SupportersRepositoryTest {

    @Test
    fun `sorts donations by most recent first and drops invalid rows`() = runTest {
        val repository = SupportersRepository(
            donationsApi = FakeDonationsApi(
                response = Response.success(
                    DonationsResponseDto(
                        donations = listOf(
                            donation(name = "Older", date = "2024-01-01T00:00:00Z"),
                            donation(name = "Newest", date = "2025-01-01T00:00:00Z", message = "Great app"),
                            donation(name = " ", date = "2025-01-02T00:00:00Z")
                        )
                    )
                )
            )
        )

        val result = repository.getSupporters()

        assertTrue(result.isSuccess)
        val supporters = result.getOrThrow()
        assertEquals(listOf("Newest", "Older"), supporters.map { it.name })
        assertEquals("Great app", supporters.first().message)
    }

    @Test
    fun `returns failure on api error`() = runTest {
        val repository = SupportersRepository(
            donationsApi = FakeDonationsApi(
                response = Response.error(
                    500,
                    "{}".toResponseBody("application/json".toMediaType())
                )
            )
        )

        val result = repository.getSupporters()

        assertTrue(result.isFailure)
    }

    private fun donation(
        name: String,
        date: String,
        message: String? = null
    ) = DonationDto(
        name = name,
        amount = 10.0,
        currency = "USD",
        date = date,
        message = message
    )

    private class FakeDonationsApi(
        private val response: Response<DonationsResponseDto>
    ) : DonationsApi {
        override suspend fun getDonations(limit: Int): Response<DonationsResponseDto> = response
    }
}
