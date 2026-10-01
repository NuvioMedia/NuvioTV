package com.nuvio.tv.domain.model

import com.squareup.moshi.JsonWriter
import java.security.MessageDigest
import okio.Buffer

/** Identifies the installed provider, independently of catalog and stream capabilities. */
data class PluginSourceRef(
    val kind: RepositoryType,
    val repositoryId: String,
    val scraperId: String,
    val providerKey: String? = null
) {
    init {
        require(repositoryId.isNotBlank())
        require(scraperId.isNotBlank())
        require(if (kind == RepositoryType.EXTERNAL_DEX) !providerKey.isNullOrBlank() else providerKey == null)
    }

    internal fun identityParts(): List<String?> = listOf(kind.name, repositoryId, scraperId, providerKey)
}

data class PluginCapabilities(
    val supportsCatalog: Boolean = false,
    val supportsStreams: Boolean = false
)

/** Metadata lookup identifiers must never replace the provider's content identity. */
data class PluginExternalIds(
    val tmdbId: String? = null,
    val imdbId: String? = null
)

data class PluginContentRef(
    val source: PluginSourceRef,
    val contentId: String,
    val type: ContentType,
    val url: String? = null,
    val externalIds: PluginExternalIds = PluginExternalIds()
) {
    init {
        require(contentId.isNotBlank())
        require(type == ContentType.MOVIE || type == ContentType.SERIES)
        require(url == null || url.isNotBlank())
    }

    fun stableId(): String = pluginIdentity(
        "plugin",
        source.identityParts() + listOf(type.toApiString(), contentId)
    )
}

data class PluginCatalogDescriptor(
    val id: String,
    val name: String,
    val type: ContentType,
    val supportsPagination: Boolean = false
) {
    init {
        require(id.isNotBlank() && name.isNotBlank())
        require(type == ContentType.MOVIE || type == ContentType.SERIES)
    }
}

/** A null token requests the first page. Tokens are opaque, not addon skip offsets. */
data class PluginCatalogRequest(
    val catalogId: String,
    val type: ContentType,
    val pageToken: String? = null,
    val language: String? = null
) {
    init {
        require(catalogId.isNotBlank())
        require(type == ContentType.MOVIE || type == ContentType.SERIES)
    }
}

data class PluginCatalogItem(
    val content: PluginContentRef,
    val name: String,
    val year: Int? = null,
    val poster: String? = null,
    val background: String? = null,
    val logo: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList()
) {
    init {
        require(name.isNotBlank())
    }

    fun toMetaPreview(): MetaPreview = MetaPreview(
        id = content.stableId(),
        type = content.type,
        name = name,
        poster = poster,
        posterShape = PosterShape.POSTER,
        background = background,
        logo = logo,
        description = description,
        releaseInfo = year?.toString(),
        imdbRating = null,
        genres = genres,
        imdbId = content.externalIds.imdbId,
        pluginContentRef = content
    )
}

data class PluginCatalogPage(
    val items: List<PluginCatalogItem>,
    val nextPageToken: String? = null
)

internal fun pluginCatalogRowKey(source: PluginSourceRef, catalogId: String, type: String): String =
    pluginIdentity("plugin-catalog", source.identityParts() + listOf(catalogTypeKey(type), catalogId))

/** Fixed-order JSON avoids delimiter collisions while preserving case and Unicode. */
private fun pluginIdentity(prefix: String, parts: List<String?>): String {
    val buffer = Buffer()
    JsonWriter.of(buffer).use { writer ->
        writer.beginArray()
        writer.value("v1")
        parts.forEach { part ->
            if (part == null) writer.nullValue() else writer.value(part)
        }
        writer.endArray()
    }
    val digest = MessageDigest.getInstance("SHA-256").digest(buffer.readByteArray())
    return "$prefix:v1:" + digest.joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
