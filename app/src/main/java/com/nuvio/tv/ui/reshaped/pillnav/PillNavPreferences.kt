package com.nuvio.tv.ui.reshaped.pillnav

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Whether the top pill menu replaces the sidebar. Off by default, like the phone app. */
internal object PillNavPreferences {
    private const val PREFS = "nuvio_pill_nav_settings"
    private const val KEY_ENABLED = "pill_nav_enabled"

    private val _enabled = MutableStateFlow(false)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = prefs(context).getBoolean(KEY_ENABLED, false)
            loaded = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        loaded = true
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** The pill menu setting as Compose state, loaded on first use. */
@Composable
internal fun rememberPillNavEnabled(): Boolean {
    PillNavPreferences.ensureLoaded(LocalContext.current)
    val enabled by PillNavPreferences.enabled.collectAsState()
    return enabled
}
