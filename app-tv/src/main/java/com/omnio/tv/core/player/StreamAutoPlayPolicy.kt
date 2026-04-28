package com.omnio.tv.core.player

import com.omnio.tv.data.local.PlayerSettings
import com.omnio.tv.data.local.StreamAutoPlayMode

object StreamAutoPlayPolicy {
    fun isEffectivelyEnabled(playerSettings: PlayerSettings): Boolean {
        if (playerSettings.streamReuseLastLinkEnabled) return true

        return when (playerSettings.streamAutoPlayMode) {
            StreamAutoPlayMode.MANUAL -> false
            StreamAutoPlayMode.FIRST_STREAM -> true
            StreamAutoPlayMode.REGEX_MATCH -> isRegexSelectionConfigured(playerSettings.streamAutoPlayRegex)
        }
    }

    fun isRegexSelectionConfigured(regexPattern: String): Boolean {
        val pattern = regexPattern.trim()
        if (pattern.isEmpty() || !pattern.any { it.isLetterOrDigit() }) return false
        return runCatching { Regex(pattern, RegexOption.IGNORE_CASE) }.isSuccess
    }
}
