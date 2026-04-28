package com.omnio.tv.ui.util

import android.content.Context
import com.omnio.tv.R

private val EPISODE_PATTERN = Regex("^Episode (\\d+)$", RegexOption.IGNORE_CASE)

fun String.localizeEpisodeTitle(context: Context): String {
    val match = EPISODE_PATTERN.matchEntire(this.trim()) ?: return this
    val number = match.groupValues[1]
    return "${context.getString(com.omnio.tv.data.R.string.episodes_episode)} $number"
}
