package com.nuvio.tv.ui.screens.player.audiosync

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings of the audio subtitle sync fallback (see AutoSync). Persistence is injected by the
 * platform, so Nuvio's own player settings are left untouched.
 */
internal object AudioSyncSettings {
    private val _fallbackEnabled = MutableStateFlow(true)

    /** Sync to the audio when AutoSync keeps a subtitle's original timing. */
    val fallbackEnabled: StateFlow<Boolean> = _fallbackEnabled.asStateFlow()

    private val _samplingOnMobileData = MutableStateFlow(false)

    /** Read short parts of the film ahead of playback on metered networks too. */
    val samplingOnMobileData: StateFlow<Boolean> = _samplingOnMobileData.asStateFlow()

    private var save: (key: String, value: Boolean) -> Unit = { _, _ -> }

    fun installPersistence(load: (key: String) -> Boolean?, save: (key: String, value: Boolean) -> Unit) {
        this.save = save
        _fallbackEnabled.value = load(FALLBACK_ENABLED) ?: true
        _samplingOnMobileData.value = load(SAMPLING_ON_MOBILE_DATA) ?: false
    }

    fun setFallbackEnabled(enabled: Boolean) {
        _fallbackEnabled.value = enabled
        save(FALLBACK_ENABLED, enabled)
    }

    fun setSamplingOnMobileData(enabled: Boolean) {
        _samplingOnMobileData.value = enabled
        save(SAMPLING_ON_MOBILE_DATA, enabled)
    }

    private const val FALLBACK_ENABLED = "audio_sync_fallback_enabled"
    private const val SAMPLING_ON_MOBILE_DATA = "audio_sync_sampling_on_mobile_data"
}
