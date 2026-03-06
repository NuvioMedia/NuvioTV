package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
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
    fun `merges duplicate logins across repos and sorts by combined contributions`() = runTest {
        val repository = GitHubContributorsRepository(
            gitHubApi = FakeGitHubApi(
                contributorsByRepo = mapOf(
                    "NuvioTV" to Response.success(
                        listOf(
                            contributor(login = "alice", contributions = 5),
                            contributor(login = "bob", contributions = 3)
                        )
                    ),
                    "nuviomobile" to Response.success(
                        listOf(
                            contributor(login = "alice", contributions = 7),
                            contributor(login = "charlie", contributions = 4)
                        )
                    )
                )
            )
        )

        val result = repository.getContributors()

        assertTrue(result.isSuccess)
        val contributors = result.getOrThrow()
        assertEquals(listOf("alice", "charlie", "bob"), contributors.map { it.login })
        assertEquals(12, contributors.first().totalContributions)
        assertEquals(5, contributors.first().tvContributions)
        assertEquals(7, contributors.first().mobileContributions)
    }

    @Test
    fun `returns successful repo data when the other repo fails`() = runTest {
        val repository = GitHubContributorsRepository(
            gitHubApi = FakeGitHubApi(
                contributorsByRepo = mapOf(
                    "NuvioTV" to Response.success(
                        listOf(
                            contributor(login = "alice", contributions = 2)
                        )
                    ),
                    "nuviomobile" to errorResponse(500)
                )
            )
        )

        val result = repository.getContributors()

        assertTrue(result.isSuccess)
        val contributors = result.getOrThrow()
        assertEquals(1, contributors.size)
        assertEquals("alice", contributors.first().login)
        assertEquals(2, contributors.first().tvContributions)
        assertEquals(0, contributors.first().mobileContributions)
    }

    @Test
    fun `fails when both repositories fail`() = runTest {
        val repository = GitHubContributorsRepository(
            gitHubApi = FakeGitHubApi(
                contributorsByRepo = mapOf(
                    "NuvioTV" to errorResponse(500),
                    "nuviomobile" to errorResponse(503)
                )
            )
        )

        val result = repository.getContributors()

        assertTrue(result.isFailure)
    }

    private fun contributor(
        login: String,
        contributions: Int,
        avatarUrl: String = "https://example.com/$login.png",
        htmlUrl: String = "https://github.com/$login"
    ) = GitHubContributorDto(
        login = login,
        avatarUrl = avatarUrl,
        htmlUrl = htmlUrl,
        contributions = contributions,
        type = "User"
    )

    private fun errorResponse(code: Int): Response<List<GitHubContributorDto>> {
        return Response.error(
            code,
            "{}".toResponseBody("application/json".toMediaType())
        )
    }

    private class FakeGitHubApi(
        private val contributorsByRepo: Map<String, Response<List<GitHubContributorDto>>>
    ) : GitHubReleaseApi {

        override suspend fun getLatestRelease(
            owner: String,
            repo: String
        ): Response<GitHubReleaseDto> {
            error("Not needed for this test")
        }

        override suspend fun getContributors(
            owner: String,
            repo: String
        ): Response<List<GitHubContributorDto>> {
            return contributorsByRepo.getValue(repo)
        }
    }
}
