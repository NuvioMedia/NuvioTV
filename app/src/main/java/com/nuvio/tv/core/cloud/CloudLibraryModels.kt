package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProvider

enum class CloudLibraryItemType {
    Torrent,
    Usenet,
    WebDownload,
    File
}

data class CloudLibraryFile(
    val id: String?,
    val name: String,
    val sizeBytes: Long? = null,
    val mimeType: String? = null,
    val playable: Boolean = true,
    val playbackUrl: String? = null
) {
    val stableKey: String
        get() = id ?: name
}

data class CloudLibraryItem(
    val providerId: String,
    val providerName: String,
    val id: String,
    val type: CloudLibraryItemType,
    val name: String,
    val status: String? = null,
    val sizeBytes: Long? = null,
    val progressFraction: Float? = null,
    val files: List<CloudLibraryFile> = emptyList()
) {
    val stableKey: String
        get() = "$providerId:${type.name}:$id"

    val playableFiles: List<CloudLibraryFile>
        get() = files.filter { it.playable }
}

data class CloudLibraryProviderState(
    val provider: DebridProvider,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val items: List<CloudLibraryItem> = emptyList()
) {
    val providerId: String
        get() = provider.id

    val providerName: String
        get() = provider.displayName
}

data class CloudLibraryUiState(
    val isLoaded: Boolean = false,
    val isEnabled: Boolean = true,
    val isRefreshing: Boolean = false,
    val providers: List<CloudLibraryProviderState> = emptyList()
) {
    val items: List<CloudLibraryItem>
        get() = providers.flatMap { it.items }

    val hasConnectedProvider: Boolean
        get() = providers.isNotEmpty()
}

sealed interface CloudLibraryPlaybackResult {
    data class Success(
        val url: String,
        val filename: String? = null,
        val videoSizeBytes: Long? = null
    ) : CloudLibraryPlaybackResult

    data object MissingCredentials : CloudLibraryPlaybackResult
    data object NotPlayable : CloudLibraryPlaybackResult
    data class Failed(val message: String? = null) : CloudLibraryPlaybackResult
}

data class CloudLibraryPlaybackInfo(
    val item: CloudLibraryItem,
    val file: CloudLibraryFile,
    val url: String,
    val filename: String? = null,
    val videoSizeBytes: Long? = null
)
