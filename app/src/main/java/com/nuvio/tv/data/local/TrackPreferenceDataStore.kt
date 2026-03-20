package com.nuvio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.nuvio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TrackPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "track_preference"
        private const val SUB_TYPE = "sub_type"
        private const val SUB_LANG = "sub_lang"
        private const val SUB_NAME = "sub_name"
        private const val SUB_TRACK_ID = "sub_track_id"
        private const val SUB_ADDON_ID = "sub_addon_id"
        private const val SUB_ADDON_URL = "sub_addon_url"
        private const val SUB_ADDON_NAME = "sub_addon_name"
        private const val AUDIO_LANG = "audio_lang"
        private const val AUDIO_NAME = "audio_name"
        private const val AUDIO_TRACK_ID = "audio_track_id"
    }

    private fun store() = factory.get(profileManager.activeProfileId.value, FEATURE)

    private fun key(field: String, contentId: String) =
        stringPreferencesKey("$field|$contentId")

    suspend fun save(contentId: String, pref: PersistedTrackPreference) {
        store().edit { prefs ->
            fun set(field: String, value: String?) {
                val k = key(field, contentId)
                if (value != null) prefs[k] = value else prefs.remove(k)
            }
            set(SUB_TYPE, pref.subtitleType)
            set(SUB_LANG, pref.subtitleLanguage)
            set(SUB_NAME, pref.subtitleName)
            set(SUB_TRACK_ID, pref.subtitleTrackId)
            set(SUB_ADDON_ID, pref.addonSubtitleId)
            set(SUB_ADDON_URL, pref.addonSubtitleUrl)
            set(SUB_ADDON_NAME, pref.addonSubtitleAddonName)
            set(AUDIO_LANG, pref.audioLanguage)
            set(AUDIO_NAME, pref.audioName)
            set(AUDIO_TRACK_ID, pref.audioTrackId)
        }
    }

    suspend fun load(contentId: String): PersistedTrackPreference? {
        val prefs = store().data.first()
        val subType = prefs[key(SUB_TYPE, contentId)]
        val audioLang = prefs[key(AUDIO_LANG, contentId)]
        val audioName = prefs[key(AUDIO_NAME, contentId)]
        val audioTrackId = prefs[key(AUDIO_TRACK_ID, contentId)]
        if (subType == null && audioLang == null && audioName == null && audioTrackId == null) return null
        return PersistedTrackPreference(
            subtitleType = subType,
            subtitleLanguage = prefs[key(SUB_LANG, contentId)],
            subtitleName = prefs[key(SUB_NAME, contentId)],
            subtitleTrackId = prefs[key(SUB_TRACK_ID, contentId)],
            addonSubtitleId = prefs[key(SUB_ADDON_ID, contentId)],
            addonSubtitleUrl = prefs[key(SUB_ADDON_URL, contentId)],
            addonSubtitleAddonName = prefs[key(SUB_ADDON_NAME, contentId)],
            audioLanguage = audioLang,
            audioName = audioName,
            audioTrackId = audioTrackId
        )
    }
}

data class PersistedTrackPreference(
    val subtitleType: String?,
    val subtitleLanguage: String?,
    val subtitleName: String?,
    val subtitleTrackId: String?,
    val addonSubtitleId: String?,
    val addonSubtitleUrl: String?,
    val addonSubtitleAddonName: String?,
    val audioLanguage: String?,
    val audioName: String?,
    val audioTrackId: String?
)

internal fun PersistedTrackPreference.toTrackPreference(): com.nuvio.tv.ui.screens.player.PlayerRuntimeController.TrackPreference? {
    val audio = if (audioLanguage != null || audioName != null || audioTrackId != null) {
        com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
            language = audioLanguage,
            name = audioName,
            trackId = audioTrackId
        )
    } else null

    val subtitle = when (subtitleType) {
        "INTERNAL" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Internal(
            track = com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedTrackSelection(
                language = subtitleLanguage,
                name = subtitleName,
                trackId = subtitleTrackId
            )
        )
        "ADDON" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Addon(
            id = addonSubtitleId ?: "",
            url = addonSubtitleUrl ?: "",
            language = subtitleLanguage ?: "",
            addonName = addonSubtitleAddonName ?: ""
        )
        "DISABLED" -> com.nuvio.tv.ui.screens.player.PlayerRuntimeController.RememberedSubtitleSelection.Disabled
        else -> null
    }

    if (audio == null && subtitle == null) return null
    return com.nuvio.tv.ui.screens.player.PlayerRuntimeController.TrackPreference(
        audio = audio,
        subtitle = subtitle
    )
}
