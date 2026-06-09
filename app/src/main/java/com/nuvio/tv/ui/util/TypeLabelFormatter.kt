package com.nuvio.tv.ui.util

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import com.nuvio.tv.R

fun formatAddonTypeLabel(value: String): String {
    val trimmed = value.trim()
    if (trimmed.isEmpty()) return ""
    return trimmed.replaceFirstChar { ch ->
        if (ch.isLowerCase()) ch.titlecase() else ch.toString()
    }
}

fun localizedContentType(context: Context, contentType: String?): String = when (contentType?.lowercase()?.trim()) {
    "movie" -> context.getString(R.string.type_movie)
    "series", "tv" -> context.getString(R.string.type_series)
    else -> contentType?.let { formatAddonTypeLabel(it) } ?: ""
}

@Composable
fun localizedContentType(contentType: String?): String {
    val context = LocalContext.current
    return localizedContentType(context, contentType)
}
