package com.nuvio.tv.ui.util

fun formatAddonTypeLabel(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}
