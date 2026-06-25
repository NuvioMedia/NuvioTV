package com.nuvio.tv.ui.components

import com.nuvio.tv.domain.model.CollectionFolder

import com.nuvio.tv.domain.model.PosterShape
import com.nuvio.tv.domain.model.DYNAMIC_COLLECTION_FALLBACK_COVER_URL

fun collectionFolderCardImageUrl(
    folder: CollectionFolder,
    isFocused: Boolean
): String? {
    // GIF URL is only used as an animated overlay on focus (when focusGifEnabled is true).
    // When focusGifEnabled is off, fall back to cover image only — don't use the GIF
    // as a static poster since it would still animate via Coil's GIF decoder.
    val cover = firstNonBlank(folder.coverImageUrl)
    if (cover.isNullOrBlank() && folder.tileShape == PosterShape.LANDSCAPE) {
        return DYNAMIC_COLLECTION_FALLBACK_COVER_URL
    }
    return cover
}

private fun firstNonBlank(vararg candidates: String?): String? {
    return candidates.firstOrNull { !it.isNullOrBlank() }?.trim()
}