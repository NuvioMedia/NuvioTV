package com.nuvio.tv.ui.screens.player.autosync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AutoSync-owned preferences.
 *
 * Kept outside PlayerSettingsDataStore on purpose: upstream NuvioTV settings architecture does not
 * need new fields, migrations, or constructor wiring just for this fork feature.
 */
internal object AutoSyncPreferences {
    private const val PREFS_NAME = "nuvio_tv_autosync"
    private const val KEY_ENABLED = "automatic_subtitle_sync_enabled"
    private const val KEY_AGGRESSIVE_MODE = "automatic_subtitle_sync_aggressive_mode"
    private const val KEY_SYNC_TOLERANCE_MS = "automatic_subtitle_sync_tolerance_ms"

    private val lock = Any()
    @Volatile private var initialized = false
    private var lastStartupSessionKey: Int? = null
    private var lastStartupPlaybackKey: String? = null

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    /** Thorough search: stop only on a stronger match; the applied match is gated the same. */
    private val _aggressiveMode = MutableStateFlow(true)
    val aggressiveMode: StateFlow<Boolean> = _aggressiveMode.asStateFlow()

    /** Keep the original timing when the needed correction is at most this; 0 disables it. */
    val syncToleranceOptionsMs = listOf(0, 100, 200, 300, 400, 500)
    private val _syncToleranceMs = MutableStateFlow(0)
    val syncToleranceMs: StateFlow<Int> = _syncToleranceMs.asStateFlow()

    fun ensureLoaded(context: Context) {
        if (initialized) return
        synchronized(lock) {
            if (initialized) return
            val prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
            _aggressiveMode.value = prefs.getBoolean(KEY_AGGRESSIVE_MODE, true)
            _syncToleranceMs.value = prefs.getInt(KEY_SYNC_TOLERANCE_MS, 0)
                .takeIf { it in syncToleranceOptionsMs } ?: 0
            initialized = true
        }
    }

    fun isEnabled(context: Context): Boolean {
        ensureLoaded(context)
        return _enabled.value
    }

    fun setAggressiveMode(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (_aggressiveMode.value == enabled) return
        _aggressiveMode.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_AGGRESSIVE_MODE, enabled)
            .apply()
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        if (_enabled.value == enabled) return
        _enabled.value = enabled
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    fun setSyncToleranceMs(context: Context, toleranceMs: Int) {
        ensureLoaded(context)
        if (toleranceMs !in syncToleranceOptionsMs || _syncToleranceMs.value == toleranceMs) return
        _syncToleranceMs.value = toleranceMs
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_SYNC_TOLERANCE_MS, toleranceMs)
            .apply()
    }

    fun claimStartupRun(sessionKey: Int, playbackKey: String): Boolean {
        synchronized(lock) {
            if (!_enabled.value) return false
            if (
                lastStartupSessionKey == sessionKey &&
                lastStartupPlaybackKey == playbackKey
            ) {
                return false
            }
            lastStartupSessionKey = sessionKey
            lastStartupPlaybackKey = playbackKey
            return true
        }
    }
}
