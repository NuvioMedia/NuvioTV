package com.omnio.tv.domain.model

/**
 * Cached bridge-table row for the current user. Upstream (the Fly-hosted
 * cedya77/aiometadata instance) is the source of truth for provider keys and
 * catalog configuration; here we only keep what the app needs to render the
 * settings hub and install the addon URL.
 */
data class AioMetadataSettings(
    val enabled: Boolean = false,
    val aioUuid: String = "",
    val manifestUrl: String = "",
    val lastSyncedAt: Long = 0L,
)

/**
 * Providers upstream currently ships. Kept here so the Android UI can render
 * consistent rows even if a fresh config fetch hasn't completed yet. Keep in
 * sync with upstream when new providers are added.
 */
enum class AioMetadataProvider(val key: String, val requiresApiKey: Boolean) {
    TMDB("tmdb", requiresApiKey = true),
    TVDB("tvdb", requiresApiKey = true),
    FANART("fanart", requiresApiKey = true),
    MAL("mal", requiresApiKey = true),
    ANILIST("anilist", requiresApiKey = false),
    KITSU("kitsu", requiresApiKey = false),
    ;

    companion object {
        fun fromKey(key: String): AioMetadataProvider? = entries.firstOrNull { it.key == key }
    }
}
