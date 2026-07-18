package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
import com.nuvio.tv.data.remote.dto.GitHubReleaseDto
import com.nuvio.tv.updater.model.AppUpdate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UpdateRepository @Inject constructor(
    private val gitHubReleaseApi: GitHubReleaseApi
) {

    suspend fun getLatestUpdate(): Result<AppUpdate> {
        return runCatching {
            val owner = BuildConfig.GITHUB_OWNER
            val repo = BuildConfig.GITHUB_REPO

            val prereleasePrefix = BuildConfig.UPDATE_PRERELEASE_PREFIX
            val dto = if (prereleasePrefix.isBlank()) {
                val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
                if (!response.isSuccessful) error("GitHub API error: ${response.code()}")
                response.body()?.takeUnless { it.draft || it.prerelease }
                    ?: error("Empty or invalid GitHub release response")
            } else {
                val response = gitHubReleaseApi.getReleases(owner = owner, repo = repo)
                if (!response.isSuccessful) error("GitHub API error: ${response.code()}")
                selectLatestPrerelease(response.body().orEmpty(), prereleasePrefix)
                    ?: error("No matching prerelease found")
            }

            val tag = dto.tagName?.takeIf { it.isNotBlank() }
                ?: dto.name?.takeIf { it.isNotBlank() }
                ?: error("Release has no tag/name")

            val asset = AbiSelector.chooseBestApkAsset(dto.assets)
                ?: error("No APK asset found in release")

            AppUpdate(
                tag = tag,
                title = dto.name?.takeIf { it.isNotBlank() } ?: tag,
                notes = dto.body.orEmpty(),
                releaseUrl = dto.htmlUrl,
                assetName = asset.name,
                assetUrl = asset.browserDownloadUrl,
                assetSizeBytes = asset.size
            )
        }
    }
}

internal fun selectLatestPrerelease(
    releases: List<GitHubReleaseDto>,
    prefix: String
): GitHubReleaseDto? = releases.asSequence()
    .filter { !it.draft && it.prerelease }
    .mapNotNull { release ->
        val tag = release.tagName.orEmpty()
        val number = tag.removePrefix(prefix).toIntOrNull()
        if (tag.startsWith(prefix) && number != null) release to number else null
    }
    .maxByOrNull { it.second }
    ?.first
