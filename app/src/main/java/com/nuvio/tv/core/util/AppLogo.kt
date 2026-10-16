package com.nuvio.tv.core.util

import android.content.Context
import com.nuvio.tv.R

/**
 * Raw bytes of the app wordmark image, served to the web-config pages,
 * or null when the resource cannot be read.
 */
fun Context.readAppLogoBytes(): ByteArray? =
    try {
        resources.openRawResource(R.drawable.app_logo_wordmark).use { it.readBytes() }
    } catch (_: Exception) {
        null
    }
