package com.nuvio.tv.core.player

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.text.SubtitleParser
import com.nuvio.tv.core.player.avi.AviExtractor

/**
 * Swaps the stock AVI extractor for the app's copy in [com.nuvio.tv.core.player.avi], which also plays OpenDML
 * (AVI 2.0) files, the usual layout for AVI files over 1 GB.
 */
@UnstableApi
internal class AviExtractorsFactory(private val delegate: ExtractorsFactory) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        delegate.createExtractors().map(::swap).toTypedArray()

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>): Array<Extractor> =
        delegate.createExtractors(uri, responseHeaders).map(::swap).toTypedArray()

    private fun swap(extractor: Extractor): Extractor =
        if (extractor is androidx.media3.extractor.avi.AviExtractor) {
            AviExtractor(AviExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA, SubtitleParser.Factory.UNSUPPORTED)
        } else {
            extractor
        }
}
