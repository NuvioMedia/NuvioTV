package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.UniqueContributionsApi
import com.nuvio.tv.data.remote.dto.UniqueContributionsResponseDto
import com.nuvio.tv.data.remote.dto.UniqueContributorDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.Response

@OptIn(ExperimentalCoroutinesApi::class)
class GitHubContributorsRepositoryTest {

    @Test
    fun `uses total contributions from unique contributions api and sorts descending`() = runTest {
        val repository = GitHubContributorsRepository(
            contributionsApi = FakeUniqueContributionsApi(
                uniqueContributions = Response.success(
                    UniqueContributionsResponseDto(
                        contributors = listOf(
                            contributor(name = "bob", total = 3, tv = 3),
                            contributor(name = "alice", total = 12, tv = 5, mobile = 7),
                            contributor(name = "charlie", total = 4, mobile = 4)
                        )
                    )
                )
            ),
            contributionsBaseUrl = "https://gitserver.tapframe.space/"
        )

        val result = repository.getContributors()

        assertTrue(result.isSuccess)
        val contributors = result.getOrThrow()
        assertEquals(listOf("alice", "charlie", "bob"), contributors.map { it.name })
        assertEquals(12, contributors.first().totalContributions)
        assertEquals(5, contributors.first().tvContributions)
        assertEquals(7, contributors.first().mobileContributions)
    }

    @Test
    fun `keeps duplicate names distinct when profiles differ`() = runTest {
        val repository = GitHubContributorsRepository(
            contributionsApi = FakeUniqueContributionsApi(
                uniqueContributions = Response.success(
                    UniqueContributionsResponseDto(
                        contributors = listOf(
                            contributor(name = "Alex", profile = "https://github.com/alex-one", total = 2),
                            contributor(name = "Alex", profile = "https://github.com/alex-two", total = 1)
                        )
                    )
                )
            ),
            contributionsBaseUrl = "https://gitserver.tapframe.space/"
        )

        val contributors = repository.getContributors().getOrThrow()

        assertEquals(2, contributors.size)
        assertEquals(listOf("alex-one", "alex-two"), contributors.map { it.githubLogin })
        assertEquals(2, contributors.map { it.id }.distinct().size)
    }

    @Test
    fun `filters blank names and non-positive totals`() = runTest {
        val repository = GitHubContributorsRepository(
            contributionsApi = FakeUniqueContributionsApi(
                uniqueContributions = Response.success(
                    UniqueContributionsResponseDto(
                        contributors = listOf(
                            contributor(name = "", total = 10),
                            contributor(name = "zero", total = 0),
                            contributor(name = "alice", total = 2, tv = 2)
                        )
                    )
                )
            ),
            contributionsBaseUrl = "https://gitserver.tapframe.space/"
        )

        val contributors = repository.getContributors().getOrThrow()

        assertEquals(1, contributors.size)
        assertEquals("alice", contributors.first().name)
    }

    @Test
    fun `fails when unique contributions api fails`() = runTest {
        val repository = GitHubContributorsRepository(
            contributionsApi = FakeUniqueContributionsApi(
                uniqueContributions = errorResponse(500)
            ),
            contributionsBaseUrl = "https://gitserver.tapframe.space/"
        )

        val result = repository.getContributors()

        assertTrue(result.isFailure)
    }

    @Test
    fun `fails without configured unique contributions base url`() = runTest {
        val repository = GitHubContributorsRepository(
            contributionsApi = FakeUniqueContributionsApi(
                uniqueContributions = Response.success(UniqueContributionsResponseDto())
            ),
            contributionsBaseUrl = ""
        )

        val result = repository.getContributors()

        assertTrue(result.isFailure)
    }

    private fun contributor(
        name: String,
        total: Int,
        tv: Int = 0,
        mobile: Int = 0,
        web: Int = 0,
        avatar: String = "https://example.com/$name.png",
        profile: String = "https://github.com/$name"
    ) = UniqueContributorDto(
        name = name,
        avatar = avatar,
        profile = profile,
        tv = tv,
        mobile = mobile,
        web = web,
        total = total
    )

    private fun errorResponse(code: Int): Response<UniqueContributionsResponseDto> {
        return Response.error(
            code,
            "{}".toResponseBody("application/json".toMediaType())
        )
    }

    private class FakeUniqueContributionsApi(
        private val uniqueContributions: Response<UniqueContributionsResponseDto>
    ) : UniqueContributionsApi {

        override suspend fun getUniqueContributions(): Response<UniqueContributionsResponseDto> {
            return uniqueContributions
        }
    }
}
