package com.nuvio.tv.domain.model

data class AmbientModeSettings(
    val enabled: Boolean = true,
    val timeoutMinutes: Int = DEFAULT_TIMEOUT_MINUTES,
    val catalogKeys: List<String> = emptyList()
) {
    companion object {
        const val DEFAULT_TIMEOUT_MINUTES = 5
        const val MAX_CATALOGS = 3
        val TIMEOUT_OPTIONS_MINUTES = listOf(1, 2, 5, 10, 15, 30)
    }
}
