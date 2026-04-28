package com.nuvio.tv.updater

import com.nuvio.tv.BuildConfig
import com.nuvio.tv.data.remote.api.GitHubReleaseApi
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

            val response = gitHubReleaseApi.getLatestRelease(owner = owner, repo = repo)
            if (!response.isSuccessful) {
                error("GitHub API error: ${response.code()}")
            }

            val dto = response.body() ?: error("Empty GitHub release response")
            if (dto.draft || dto.prerelease) {
                error("Latest release is draft/prerelease")
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
