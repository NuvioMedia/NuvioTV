package com.nuvio.tv.core.image

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Returns a key that increments when [imageUrl] gets a fresh version
 * via background revalidation. Use as part of memoryCacheKey to force reload.
 */
@Composable
fun rememberImageRevalidationKey(imageUrl: String?): Int {
    var version by remember(imageUrl) { mutableIntStateOf(0) }

    if (imageUrl != null) {
        LaunchedEffect(imageUrl) {
            ImageInvalidationBus.events.collect { invalidatedUrl ->
                if (invalidatedUrl == imageUrl) {
                    version++
                }
            }
        }
    }

    return version
}
