package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.audio.AudioSink

internal object PassthroughTrackPromotion {

    fun shouldPromote(
        currentFormatSupport: Int,
        sinkFormatSupport: Int,
        policyDenies: Boolean
    ): Boolean {
        if (RendererCapabilities.getFormatSupport(currentFormatSupport) == C.FORMAT_HANDLED) return false
        if (policyDenies) return false
        return sinkFormatSupport != AudioSink.SINK_FORMAT_UNSUPPORTED
    }
}
