package com.nuvio.tv.ui.screens.player.mpvass

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor as StockMatroskaExtractor
import com.nuvio.tv.core.player.dvmkv.MatroskaExtractor as DvMatroskaExtractor
import com.nuvio.tv.ui.screens.player.replaceInnermostExtractor

@OptIn(UnstableApi::class)
internal class Extractors(
    private val delegate: ExtractorsFactory,
    private val readBack: () -> String?,
    private val sink: () -> AssSink,
    private val events: () -> MatroskaEvents,
) : ExtractorsFactory {
    override fun createExtractors() = wrapped(delegate.createExtractors(), null)

    override fun createExtractors(uri: Uri, responseHeaders: Map<String, List<String>>) =
        wrapped(delegate.createExtractors(uri, responseHeaders), uri)

    private fun wrapped(extractors: Array<Extractor>, uri: Uri?) = extractors.map<Extractor, Extractor> {
        when (val underlying = it.underlyingImplementation) {
            is DvMatroskaExtractor -> it.replaceInnermostExtractor(
                MatroskaReader(sink(), readBack, uri, events(), underlying.dolbyVisionSampleTransformer),
            )
            is StockMatroskaExtractor -> it.replaceInnermostExtractor(MatroskaReader(sink(), readBack, uri, events(), null))
            else -> it
        }
    }.toTypedArray()
}
