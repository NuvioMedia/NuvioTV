package com.nuvio.tv.core.network

import com.nuvio.tv.data.local.DeviceLocalPlayerPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UserAgentProvider @Inject constructor(
    preferences: DeviceLocalPlayerPreferences
) {
    @Volatile
    private var cached: String = ""

    init {
        preferences.customUserAgent
            .onEach { cached = it }
            .launchIn(CoroutineScope(SupervisorJob() + Dispatchers.Default))
    }

    fun currentOrDefault(default: String): String =
        cached.takeIf { it.isNotBlank() } ?: default
}