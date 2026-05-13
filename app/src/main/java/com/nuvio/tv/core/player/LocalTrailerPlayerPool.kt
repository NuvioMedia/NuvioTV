package com.nuvio.tv.core.player

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * CompositionLocal providing access to the shared [TrailerPlayerPool] singleton.
 * This avoids threading the pool through every intermediate composable parameter list.
 *
 * Provided at the Activity/NavHost level via Hilt injection.
 */
val LocalTrailerPlayerPool = staticCompositionLocalOf<TrailerPlayerPool?> { null }
