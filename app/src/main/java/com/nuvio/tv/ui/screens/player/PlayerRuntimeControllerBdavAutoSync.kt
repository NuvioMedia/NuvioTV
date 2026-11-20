package com.nuvio.tv.ui.screens.player

import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncSettings
import com.nuvio.tv.ui.screens.player.audiosync.AudioSyncTaps
import com.nuvio.tv.ui.screens.player.autosync.AutoSyncPreferences

/**
 * Blu-ray clips (BDAV) are read by their own extractors, not by the factory that
 * [autoSyncExtractorsFactory] wraps. Give them the audio taps, so the audio fallback hears the
 * disc's audio. AutoSync's embedded subtitle observer is left out: a clip has no subtitle index,
 * so the lines playback reads ahead never make the near-complete reference it needs, and decoding
 * every PGS image on the loading thread would only cost time.
 */
internal fun PlayerRuntimeController.configureBdavAutoSync() {
    mediaSourceFactory.configureBdavExtractorsWrapper { delegate, url ->
        AutoSyncPreferences.ensureLoaded(context)
        if (AutoSyncPreferences.isEnabled(context) && AudioSyncSettings.fallbackEnabled.value) {
            AudioSyncTaps.wrapExtractors(delegate, url)
        } else {
            delegate
        }
    }
}
