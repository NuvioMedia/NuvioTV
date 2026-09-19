package com.nuvio.tv.ui.screens.player

// Keep enough headroom for releases with long intros, recaps, or a different starting point.
// The Auto Sync engine derives a tighter range from the observed audio and the complete subtitle.
internal const val SUBTITLE_DELAY_MIN_MS = -21_600_000
internal const val SUBTITLE_DELAY_MAX_MS = 21_600_000
internal const val SUBTITLE_DELAY_SLIDER_MIN_MS = -180_000
internal const val SUBTITLE_DELAY_SLIDER_MAX_MS = 180_000
internal const val SUBTITLE_DELAY_STEP_MS = 100
internal const val SUBTITLE_DELAY_OVERLAY_TIMEOUT_MS = 20_000L
