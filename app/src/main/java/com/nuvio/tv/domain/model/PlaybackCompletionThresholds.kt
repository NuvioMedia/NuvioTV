package com.nuvio.tv.domain.model

object PlaybackCompletionThresholds {
    @Volatile
    private var completionThresholdFraction: Float = 0.90f

    fun getCompletionThresholdFraction(): Float = completionThresholdFraction

    fun getCompletionThresholdPercent(): Float = completionThresholdFraction * 100f

    fun setCompletionThresholdPercent(percent: Float) {
        completionThresholdFraction = (percent / 100f).coerceIn(0.90f, 0.995f)
    }
}
