package com.omnio.tv.ui.screens.player

import com.omnio.tv.data.local.PersistedTrackPreference

internal fun PersistedTrackPreference.toTrackPreference(): PlayerRuntimeController.TrackPreference? {
    val audio = if (audioLanguage != null || audioName != null || audioTrackId != null) {
        PlayerRuntimeController.RememberedTrackSelection(
            language = audioLanguage,
            name = audioName,
            trackId = audioTrackId
        )
    } else null

    val subtitle = when (subtitleType) {
        "INTERNAL" -> PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = PlayerRuntimeController.RememberedTrackSelection(
                language = subtitleLanguage,
                name = subtitleName,
                trackId = subtitleTrackId
            )
        )
        "ADDON" -> PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addonSubtitleId ?: "",
            url = addonSubtitleUrl ?: "",
            language = subtitleLanguage ?: "",
            addonName = addonSubtitleAddonName ?: ""
        )
        "DISABLED" -> PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }

    if (audio == null && subtitle == null) return null
    return PlayerRuntimeController.TrackPreference(
        audio = audio,
        subtitle = subtitle
    )
}
