package com.nuvio.tv.core.cloud

import com.nuvio.tv.core.debrid.DebridProvider

interface CloudLibraryProviderApi {
    val provider: DebridProvider

    suspend fun listItems(apiKey: String): Result<List<CloudLibraryItem>>

    suspend fun resolvePlayback(
        apiKey: String,
        item: CloudLibraryItem,
        file: CloudLibraryFile
    ): CloudLibraryPlaybackResult
}
