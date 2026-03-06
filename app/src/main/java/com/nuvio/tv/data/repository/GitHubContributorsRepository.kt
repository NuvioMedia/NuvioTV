package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubContributorDto
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import javax.inject.Inject
import javax.inject.Singleton

data class GitHubContributor(
    val login: String,
    val avatarUrl: String?,
    val profileUrl: String?,
    val totalContributions: Int,
    val tvContributions: Int,
    val mobileContributions: Int
)

@Singleton
class GitHubContributorsRepository @Inject constructor(
    private val gitHubApi: GitHubReleaseApi
) {

    suspend fun getContributors(): Result<List<GitHubContributor>> = runCatching {
        coroutineScope {
            val tvDeferred = async { fetchRepoContributors(repo = TV_REPOSITORY) }
            val mobileDeferred = async { fetchRepoContributors(repo = MOBILE_REPOSITORY) }

            val tvResult = tvDeferred.await()
            val mobileResult = mobileDeferred.await()

            if (tvResult.isFailure && mobileResult.isFailure) {
                throw (tvResult.exceptionOrNull() ?: mobileResult.exceptionOrNull() ?: IllegalStateException("Unable to load contributors"))
            }

            mergeContributors(
                tvContributors = tvResult.getOrDefault(emptyList()),
                mobileContributors = mobileResult.getOrDefault(emptyList())
            )
        }
    }

    private suspend fun fetchRepoContributors(repo: String): Result<List<GitHubContributorDto>> = runCatching {
        val response = gitHubApi.getContributors(owner = OWNER, repo = repo)
        if (!response.isSuccessful) {
            error("GitHub contributors API error for $repo: ${response.code()}")
        }
        response.body().orEmpty()
    }

    private fun mergeContributors(
        tvContributors: List<GitHubContributorDto>,
        mobileContributors: List<GitHubContributorDto>
    ): List<GitHubContributor> {
        val contributorsByLogin = linkedMapOf<String, MutableGitHubContributor>()

        tvContributors.forEach { dto ->
            val normalized = dto.toNormalizedContributor() ?: return@forEach
            val existing = contributorsByLogin.getOrPut(normalized.login) {
                MutableGitHubContributor(
                    login = normalized.login,
                    avatarUrl = normalized.avatarUrl,
                    profileUrl = normalized.htmlUrl
                )
            }
            existing.avatarUrl = existing.avatarUrl ?: normalized.avatarUrl
            existing.profileUrl = existing.profileUrl ?: normalized.htmlUrl
            existing.tvContributions += normalized.contributions
        }

        mobileContributors.forEach { dto ->
            val normalized = dto.toNormalizedContributor() ?: return@forEach
            val existing = contributorsByLogin.getOrPut(normalized.login) {
                MutableGitHubContributor(
                    login = normalized.login,
                    avatarUrl = normalized.avatarUrl,
                    profileUrl = normalized.htmlUrl
                )
            }
            existing.avatarUrl = existing.avatarUrl ?: normalized.avatarUrl
            existing.profileUrl = existing.profileUrl ?: normalized.htmlUrl
            existing.mobileContributions += normalized.contributions
        }

        return contributorsByLogin.values
            .map { contributor ->
                GitHubContributor(
                    login = contributor.login,
                    avatarUrl = contributor.avatarUrl,
                    profileUrl = contributor.profileUrl,
                    totalContributions = contributor.tvContributions + contributor.mobileContributions,
                    tvContributions = contributor.tvContributions,
                    mobileContributions = contributor.mobileContributions
                )
            }
            .sortedWith(
                compareByDescending<GitHubContributor> { it.totalContributions }
                    .thenByDescending { it.tvContributions }
                    .thenByDescending { it.mobileContributions }
                    .thenBy { it.login.lowercase() }
            )
    }

    private fun GitHubContributorDto.toNormalizedContributor(): NormalizedContributor? {
        val normalizedLogin = login?.trim().orEmpty()
        val normalizedContributions = contributions ?: 0
        if (normalizedLogin.isBlank() || normalizedContributions <= 0) return null
        if (type.equals("Bot", ignoreCase = true)) return null

        return NormalizedContributor(
            login = normalizedLogin,
            avatarUrl = avatarUrl?.takeIf { it.isNotBlank() },
            htmlUrl = htmlUrl?.takeIf { it.isNotBlank() },
            contributions = normalizedContributions
        )
    }

    private data class NormalizedContributor(
        val login: String,
        val avatarUrl: String?,
        val htmlUrl: String?,
        val contributions: Int
    )

    private data class MutableGitHubContributor(
        val login: String,
        var avatarUrl: String?,
        var profileUrl: String?,
        var tvContributions: Int = 0,
        var mobileContributions: Int = 0
    )

    private companion object {
        const val OWNER = "tapframe"
        const val TV_REPOSITORY = "NuvioTV"
        const val MOBILE_REPOSITORY = "nuviomobile"
    }
}
