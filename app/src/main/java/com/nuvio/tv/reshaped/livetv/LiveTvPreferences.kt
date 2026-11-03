package com.nuvio.tv.reshaped.livetv

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether Live TV shows in the menu (off by default), and whether its list previews channels, with sound (both on). */
object LiveTvPreferences {
    private const val KEY_ENABLED = "live_tv_enabled"
    private const val KEY_PREVIEWS = "live_tv_previews"
    private const val KEY_PREVIEW_SOUND = "live_tv_preview_sound"
    private const val KEY_SHOW_FAVORITES = "live_tv_show_favorites"
    private const val KEY_SHOW_ALL = "live_tv_show_all"
    private const val KEY_PREFER_HLS = "live_tv_prefer_hls"
    private const val KEY_GUIDE_REFRESH_HOURS = "live_tv_guide_refresh_hours"

    /** How often a saved guide is downloaded again; the first is the default. */
    val guideRefreshOptionsHours = listOf(12, 24, 48)

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _previews = MutableStateFlow(true)
    /** A small live picture of the focused channel in the list. */
    val previews: StateFlow<Boolean> = _previews.asStateFlow()

    private val _previewSound = MutableStateFlow(true)
    /** Whether the preview plays the channel's sound. */
    val previewSound: StateFlow<Boolean> = _previewSound.asStateFlow()

    private val _showFavorites = MutableStateFlow(true)
    /** Whether the categories list Favorites. */
    val showFavorites: StateFlow<Boolean> = _showFavorites.asStateFlow()

    private val _showAll = MutableStateFlow(true)
    /** Whether the categories list All channels. */
    val showAll: StateFlow<Boolean> = _showAll.asStateFlow()

    private val _preferHls = MutableStateFlow(false)
    /** Replays (catch-up) ask an Xtream panel for HLS first, which has a length and seeks; TS when it has none. */
    val preferHls: StateFlow<Boolean> = _preferHls.asStateFlow()

    private val _guideRefreshHours = MutableStateFlow(guideRefreshOptionsHours.first())
    val guideRefreshHours: StateFlow<Int> = _guideRefreshHours.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val prefs = prefs(context)
            _enabled.value = prefs.getBoolean(KEY_ENABLED, false)
            _previews.value = prefs.getBoolean(KEY_PREVIEWS, true)
            _previewSound.value = prefs.getBoolean(KEY_PREVIEW_SOUND, true)
            _showFavorites.value = prefs.getBoolean(KEY_SHOW_FAVORITES, true)
            _showAll.value = prefs.getBoolean(KEY_SHOW_ALL, true)
            _preferHls.value = prefs.getBoolean(KEY_PREFER_HLS, false)
            _guideRefreshHours.value = prefs.getInt(KEY_GUIDE_REFRESH_HOURS, guideRefreshOptionsHours.first())
                .takeIf { it in guideRefreshOptionsHours } ?: guideRefreshOptionsHours.first()
            loaded = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        loaded = true
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setPreviews(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _previews.value = enabled
        prefs(context).edit().putBoolean(KEY_PREVIEWS, enabled).apply()
    }

    fun setPreviewSound(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _previewSound.value = enabled
        prefs(context).edit().putBoolean(KEY_PREVIEW_SOUND, enabled).apply()
    }

    fun setShowFavorites(context: Context, shown: Boolean) {
        ensureLoaded(context)
        _showFavorites.value = shown
        prefs(context).edit().putBoolean(KEY_SHOW_FAVORITES, shown).apply()
    }

    fun setShowAll(context: Context, shown: Boolean) {
        ensureLoaded(context)
        _showAll.value = shown
        prefs(context).edit().putBoolean(KEY_SHOW_ALL, shown).apply()
    }

    fun setPreferHls(context: Context, enabled: Boolean) {
        ensureLoaded(context)
        _preferHls.value = enabled
        prefs(context).edit().putBoolean(KEY_PREFER_HLS, enabled).apply()
    }

    fun setGuideRefreshHours(context: Context, hours: Int) {
        if (hours !in guideRefreshOptionsHours) return
        ensureLoaded(context)
        _guideRefreshHours.value = hours
        prefs(context).edit().putInt(KEY_GUIDE_REFRESH_HOURS, hours).apply()
    }

    // Its own tiny file: this is read on the main thread when the menu is built.
    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("nuvio_live_tv_menu", Context.MODE_PRIVATE)
}

/** The Live TV menu setting as Compose state, loaded on first use. */
@Composable
fun rememberLiveTvEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.enabled.collectAsState()
    return enabled
}

/** The channel preview setting as Compose state. */
@Composable
fun rememberLiveTvPreviewsEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.previews.collectAsState()
    return enabled
}

/** The preview sound setting as Compose state. */
@Composable
fun rememberLiveTvPreviewSoundEnabled(): Boolean {
    LiveTvPreferences.ensureLoaded(LocalContext.current)
    val enabled by LiveTvPreferences.previewSound.collectAsState()
    return enabled
}

/** The Live TV screen's navigation route. */
const val LIVE_TV_ROUTE = "reshaped_live_tv"
