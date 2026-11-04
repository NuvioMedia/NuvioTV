package com.nuvio.tv.ui.screens.calendar

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ReleaseCalendarPreferences {
    private const val KEY_ENABLED = "release_calendar_enabled"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    @Volatile
    private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            _enabled.value = prefs(context).getBoolean(KEY_ENABLED, true)
            loaded = true
        }
    }

    fun setEnabled(context: Context, enabled: Boolean) {
        _enabled.value = enabled
        loaded = true
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences("nuvio_release_calendar_menu", Context.MODE_PRIVATE)
}

@Composable
fun rememberReleaseCalendarEnabled(): Boolean {
    ReleaseCalendarPreferences.ensureLoaded(LocalContext.current)
    val enabled by ReleaseCalendarPreferences.enabled.collectAsState()
    return enabled
}

const val RELEASE_CALENDAR_ROUTE = "release_calendar"
