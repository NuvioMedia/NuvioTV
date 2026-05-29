package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProviders
import com.nuvio.tv.data.remote.api.PremiumizeApi
import com.nuvio.tv.data.remote.dto.PremiumizeCloudFileDto
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException

@Singleton
class PremiumizeCloudLibraryProviderApi @Inject constructor(
    private val api: PremiumizeApi
) : CloudLibraryProviderApi {
    override val provider = DebridProviders.Premiumize

    override suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>> =
        runCatching {
            val response = api.listAllItems("Bearer $apiKey")
            if (!response.isSuccessful || response.body()?.status.equals("error", ignoreCase = true)) {
                throw IllegalStateException(response.body()?.message ?: response.body()?.code ?: response.errorBody()?.string().orEmpty().ifBlank { null })
            }
            premiumizeCloudItemsFromFiles(
                files = response.body()?.files.orEmpty(),
                providerId = provider.id,
                providerName = provider.displayName
            )
        }

    override suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult {
        if (!file.playable) return CloudLibraryPlaybackResult.NotPlayable
        file.playbackUrl?.takeIf { it.isNotBlank() }?.let { url ->
            return CloudLibraryPlaybackResult.Success(
                url = url,
                filename = file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = file.sizeBytes
            )
        }

        val fileId = file.id?.takeIf { it.isNotBlank() } ?: return CloudLibraryPlaybackResult.Failed()
        return try {
            val response = api.itemDetails(authorization = "Bearer $apiKey", itemId = fileId)
            if (!response.isSuccessful || response.body()?.status.equals("error", ignoreCase = true)) {
                return CloudLibraryPlaybackResult.Failed(response.body()?.message ?: response.body()?.code)
            }
            val body = response.body()
            val url = body?.link?.takeIf { it.isNotBlank() }
                ?: return CloudLibraryPlaybackResult.Failed()
            CloudLibraryPlaybackResult.Success(
                url = url,
                filename = body.name?.takeIf { it.isNotBlank() } ?: file.name.takeIf { it.isNotBlank() },
                videoSizeBytes = body.size ?: file.sizeBytes
            )
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            CloudLibraryPlaybackResult.Failed(error.message)
        }
    }
}

fun premiumizeCloudItemsFromFiles(
    files: List<PremiumizeCloudFileDto>,
    providerId: String,
    providerName: String
): List<CloudLibraryItem> {
    val mappedFiles = files.mapNotNull { it.toPremiumizeCloudFile() }
    val groups = mappedFiles.groupBy { file ->
        file.groupKey
    }
    return groups.values
        .mapNotNull { group ->
            val first = group.firstOrNull() ?: return@mapNotNull null
            val cloudFiles = group
                .map { it.file }
                .sortedWith(compareBy<CloudLibraryFile> { !it.playable }.thenBy { it.name.lowercase() })
            val size = cloudFiles
                .mapNotNull { it.sizeBytes }
                .takeIf { it.isNotEmpty() }
                ?.sum()
            CloudLibraryItem(
                providerId = providerId,
                providerName = providerName,
                id = first.itemId,
                type = CloudLibraryItemType.File,
                name = first.itemName,
                status = "Ready",
                sizeBytes = size,
                files = cloudFiles
            )
        }
        .sortedBy { it.name.lowercase() }
}

private data class PremiumizeMappedCloudFile(
    val groupKey: String,
    val itemId: String,
    val itemName: String,
    val file: CloudLibraryFile
)

private fun PremiumizeCloudFileDto.toPremiumizeCloudFile(): PremiumizeMappedCloudFile? {
    val normalizedPath = path?.trim()?.trim('/')?.takeIf { it.isNotBlank() }
    val fileName = name?.trim()?.takeIf { it.isNotBlank() }
        ?: normalizedPath?.pathBasename()?.takeIf { it.isNotBlank() }
        ?: return null
    val fileId = id?.trim()?.takeIf { it.isNotBlank() }
    val playable = isPlayablePremiumizeCloudFile(name = fileName, mimeType = mimeType)
    val segments = normalizedPath
        ?.split('/')
        ?.map { it.trim() }
        ?.filter { it.isNotBlank() }
        .orEmpty()
    val topLevel = segments.firstOrNull()
    val isRootFile = segments.size <= 1
    val itemName = if (isRootFile) fileName else topLevel ?: fileName
    val itemId = if (isRootFile) {
        "file:${fileId ?: normalizedPath ?: fileName}"
    } else {
        "folder:${topLevel ?: itemName}"
    }
    val groupKey = if (isRootFile) itemId else "folder:${topLevel ?: itemName}"
    return PremiumizeMappedCloudFile(
        groupKey = groupKey,
        itemId = itemId,
        itemName = itemName,
        file = CloudLibraryFile(
            id = fileId,
            name = fileName,
            sizeBytes = size,
            mimeType = mimeType,
            playable = playable,
            playbackUrl = link?.takeIf { playable && it.isNotBlank() }
        )
    )
}

private fun String.pathBasename(): String =
    substringAfterLast('/').substringAfterLast('\\')

private fun isPlayablePremiumizeCloudFile(name: String, mimeType: String?): Boolean {
    val normalizedMime = mimeType?.lowercase().orEmpty()
    if (normalizedMime.startsWith("video/")) return true
    val extension = name.substringAfterLast('.', missingDelimiterValue = "")
        .lowercase()
    return extension in premiumizePlayableVideoExtensions
}

private val premiumizePlayableVideoExtensions = setOf(
    "3g2",
    "3gp",
    "avi",
    "divx",
    "flv",
    "m2ts",
    "m4v",
    "mkv",
    "mov",
    "mp4",
    "mpeg",
    "mpg",
    "mts",
    "ogm",
    "ogv",
    "ts",
    "webm",
    "wmv"
)
