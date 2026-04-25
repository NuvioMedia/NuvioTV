package com.nuvio.tv.data.repository

import com.nuvio.tv.data.remote.api.UniqueContributionsApi
import com.nuvio.tv.data.remote.dto.UniqueContributorDto
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

data class GitHubContributor(
    val id: String,
    val name: String,
    val githubLogin: String?,
    val avatarUrl: String?,
    val profileUrl: String?,
    val totalContributions: Int,
    val tvContributions: Int,
    val mobileContributions: Int,
    val webContributions: Int
)

@Singleton
class GitHubContributorsRepository @Inject constructor(
    private val contributionsApi: UniqueContributionsApi,
    @param:Named("uniqueContributionsBaseUrl") private val contributionsBaseUrl: String
) {

    suspend fun getContributors(): Result<List<GitHubContributor>> = runCatching {
        if (contributionsBaseUrl.isBlank()) {
            error("Contributors API is not configured.")
        }

        val response = contributionsApi.getUniqueContributions()
        if (!response.isSuccessful) {
            error("Contributors API error: ${response.code()}")
        }

        response.body()
            ?.contributors
            .orEmpty()
            .mapIndexedNotNull { index, contributor -> contributor.toContributor(index) }
            .sortedWith(
                compareByDescending<GitHubContributor> { it.totalContributions }
                    .thenByDescending { it.tvContributions }
                    .thenByDescending { it.mobileContributions }
                    .thenByDescending { it.webContributions }
                    .thenBy { it.name.lowercase() }
            )
    }

    private fun UniqueContributorDto.toContributor(index: Int): GitHubContributor? {
        val normalizedName = name?.trim().orEmpty()
        val normalizedTotal = total ?: 0
        if (normalizedName.isBlank() || normalizedTotal <= 0) return null

        val normalizedProfile = profile?.takeIf { it.isNotBlank() }

        return GitHubContributor(
            id = normalizedProfile ?: "$normalizedName|$index",
            name = normalizedName,
            githubLogin = normalizedProfile?.substringAfterLast('/')?.takeIf { it.isNotBlank() },
            avatarUrl = avatar?.takeIf { it.isNotBlank() },
            profileUrl = normalizedProfile,
            totalContributions = normalizedTotal,
            tvContributions = tv ?: 0,
            mobileContributions = mobile ?: 0,
            webContributions = web ?: 0
        )
    }
}
