package com.nuvio.tv.ui.screens.stream

import com.nuvio.tv.domain.model.WatchProgress

internal fun streamResumeProgress(progress: WatchProgress?, startFromBeginning: Boolean): WatchProgress? =
    progress?.takeUnless {
        startFromBeginning || it.isCompleted() ||
            (it.position <= 0L && it.progressPercentage <= 0f)
    }
