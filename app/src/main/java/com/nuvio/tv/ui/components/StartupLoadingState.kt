package com.nuvio.tv.ui.components

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

class StartupLoadingState {
    var complete by mutableStateOf(false)
}

val LocalStartupLoadingState = compositionLocalOf<StartupLoadingState?> { null }
val LocalStartupSplashEnabled = compositionLocalOf { false }
