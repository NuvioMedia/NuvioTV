package com.omnio.tv.domain.model

/**
 * How a non-primary profile's AIOMetadata config relates to the Main profile's.
 *
 * - [FULL_MIRROR]: profile keeps its own upstream UUID but its config is kept
 *   in lock-step with Main's — every Main update fans out the full config
 *   (api keys, providers, catalogs, settings) to this profile's UUID.
 * - [KEYS_ONLY]: profile keeps its own UUID, providers/catalogs/settings —
 *   typically Kids-tuned. Only the `apiKeys` slice is mirrored from Main, so
 *   when the user rotates a TMDB/TVDB key on Main it propagates here.
 * - [INDEPENDENT]: snapshot at provision, no further propagation. The user is
 *   responsible for editing this profile's config separately.
 *
 * For Kids profiles, [FULL_MIRROR] is invalid (it would discard the kid-tuned
 * catalogs); ProfileManager clamps Kids to either [KEYS_ONLY] or [INDEPENDENT].
 * The Main profile (id=1) is always [INDEPENDENT] because it is the source.
 */
enum class AioSharingMode {
    FULL_MIRROR,
    KEYS_ONLY,
    INDEPENDENT;

    val mirrorsApiKeys: Boolean get() = this == FULL_MIRROR || this == KEYS_ONLY
    val mirrorsFullConfig: Boolean get() = this == FULL_MIRROR

    companion object {
        fun fromStorageString(raw: String?): AioSharingMode = when (raw?.uppercase()) {
            "FULL_MIRROR" -> FULL_MIRROR
            "KEYS_ONLY" -> KEYS_ONLY
            else -> INDEPENDENT
        }
    }
}
