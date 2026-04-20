package com.omnio.tv.domain.model

/**
 * Per-user AIOMetadata configuration, cached locally and mirrored from Supabase.
 *
 * - [token] is opaque and embedded in the addon manifest URL.
 * - [providerKeys] holds the raw user-supplied API keys (masked in UI on display).
 * - [providerEnabled] toggles whether each provider participates in responses.
 * - [manifestUrl] is the full addon URL we install into the addon list when enabled.
 */
data class AioMetadataSettings(
    val enabled: Boolean = false,
    val token: String = "",
    val providerEnabled: Map<AioMetadataProvider, Boolean> = emptyMap(),
    val providerKeys: Map<AioMetadataProvider, String> = emptyMap(),
    val manifestUrl: String = "",
    val webUrl: String = "",
    val lastSyncedAt: Long = 0L,
)

/**
 * Providers the AIOMetadata addon knows about. The concrete list should match
 * the upstream fork we vendor into the Edge Function; adjust when the fork is chosen.
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
