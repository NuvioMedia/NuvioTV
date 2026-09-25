package com.nuvio.tv.core.player.asf

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory

@UnstableApi
internal class AsfExtractorsFactory(
    private val delegate: ExtractorsFactory,
) : ExtractorsFactory {

    override fun createExtractors(): Array<Extractor> =
        arrayOf<Extractor>(AsfExtractor()) + delegate.createExtractors()

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> =
        arrayOf<Extractor>(AsfExtractor()) + delegate.createExtractors(uri, responseHeaders)
}
