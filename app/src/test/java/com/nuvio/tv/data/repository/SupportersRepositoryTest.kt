package com.nuvio.tv.data.repository

import android.content.Context
import com.nuvio.tv.data.remote.api.SupportersApi
import com.nuvio.tv.data.remote.dto.SupportersWallGroupDto
import com.nuvio.tv.data.remote.dto.SupportersWallMemberDto
import com.nuvio.tv.data.remote.dto.SupportersWallResponseDto
import com.nuvio.tv.domain.model.MemberTier
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class SupportersRepositoryTest {
    private val context = io.mockk.mockk<Context>(relaxed = true)

    @Test
    fun `uses top members in endpoint order and drops invalid rows`() = runTest {
        val repository = SupportersRepository(
            appContext = context,
            supportersApi = FakeSupportersApi(
                response = Response.success(
                    SupportersWallResponseDto(
                        top = SupportersWallGroupDto(
                            members = listOf(
                                member(
                                    name = "Top Supporter",
                                    membershipLevel = "SUPPORTER_PLUS",
                                    supporterSince = "2026-08-19T04:08:39.423+00:00",
                                    avatarUrl = "https://example.com/top.png"
                                ),
                                member(
                                    name = "Second Supporter",
                                    membershipLevel = "SUPPORTER",
                                    supporterSince = null
                                ),
                                member(name = " ", membershipLevel = "SUPPORTER"),
                                member(name = "Unknown Tier", membershipLevel = "UNKNOWN")
                            ),
                            totalCount = 4
                        )
                    )
                )
            )
        )

        val result = repository.getSupporters()

        assertTrue(result.isSuccess)
        val supporters = result.getOrThrow()
        assertEquals(listOf("Top Supporter", "Second Supporter"), supporters.map { it.name })
        assertEquals(MemberTier.SUPPORTER_PLUS, supporters.first().membershipLevel)
        assertEquals("https://example.com/top.png", supporters.first().avatarUrl)
        assertNull(supporters.last().supporterSince)
    }

    @Test
    fun `returns failure on api error`() = runTest {
        val repository = SupportersRepository(
            appContext = context,
            supportersApi = FakeSupportersApi(
                response = Response.error(
                    500,
                    "{}".toResponseBody("application/json".toMediaType())
                )
            )
        )

        val result = repository.getSupporters()

        assertTrue(result.isFailure)
    }

    private fun member(
        name: String,
        membershipLevel: String,
        supporterSince: String? = null,
        avatarUrl: String? = null
    ) = SupportersWallMemberDto(
        displayName = name,
        avatarUrl = avatarUrl,
        membershipLevel = membershipLevel,
        supporterSince = supporterSince
    )

    private class FakeSupportersApi(
        private val response: Response<SupportersWallResponseDto>
    ) : SupportersApi {
        override suspend fun getSupportersWall(): Response<SupportersWallResponseDto> = response
    }
}
