@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.media3.extractor.Extractor

/**
 * An extractor wrapper that forwards everything to [wrappedExtractor] and can be rebuilt around a
 * different inner extractor. Lets the libass Matroska swap replace the innermost extractor while
 * keeping the wrappers around it (e.g. AutoSync's embedded subtitle observer).
 */
internal interface RewrappableExtractor : Extractor {
    val wrappedExtractor: Extractor

    fun rewrap(inner: Extractor): Extractor
}

/** This extractor's wrappers rebuilt around [replacement] in place of the innermost extractor. */
internal fun Extractor.replaceInnermostExtractor(replacement: Extractor): Extractor =
    if (this is RewrappableExtractor) {
        rewrap(wrappedExtractor.replaceInnermostExtractor(replacement))
    } else {
        replacement
    }
