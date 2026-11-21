package com.nuvio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import coil3.request.ImageRequest
import com.nuvio.tv.core.image.collectionCover
import com.nuvio.tv.domain.model.CollectionFolder

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // When focusGifEnabled is off, fall back to cover image only — don't use the GIF
    // as a static poster since it would still animate via Coil's GIF decoder.
    return firstNonBlank(folder.coverImageUrl)
}

@Composable
internal fun rememberCollectionFolderCover(url: String?): ImageRequest? {
    val context = LocalContext.current
    return remember(context, url) {
        url?.let { ImageRequest.Builder(context).data(it).collectionCover().build() }
    }
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}
