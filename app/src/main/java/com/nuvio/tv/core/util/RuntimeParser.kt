package com.nuvio.tv.core.util

fun parseRuntimeMinutes(runtime: String?): Int? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val hours = Regex("(\\d+)\\s*h").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    val minutes = Regex("(\\d+)\\s*m(?:in)?").find(normalized)?.groupValues?.getOrNull(1)?.toIntOrNull()
    if (hours != null || minutes != null) return (hours ?: 0) * 60 + (minutes ?: 0)
    return normalized.filter(Char::isDigit).toIntOrNull()
}
