package com.nuvio.tv.reshaped.livetv

import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ts.DefaultTsPayloadReaderFactory

/**
 * MPEG-TS reader flags for Live TV channels. Many broadcast restreams send H.264 with IDR frames
 * only every several seconds, or none at all (just recovery-point I-frames). Media3 then waits for
 * an IDR frame, so the channel starts late or times out; with this flag it starts on the first
 * I-frame, as IPTV players and Media3's own HLS reader do. Files keep Nuvio's flags.
 */
@OptIn(UnstableApi::class)
object LiveTvTsFlags {
    /** Flags to add for [url]: none unless the player is opening a Live TV channel. */
    fun extraFor(url: String): Int =
        if (LiveTvPlaybackRegistry.isLiveTv(url)) {
            DefaultTsPayloadReaderFactory.FLAG_ALLOW_NON_IDR_KEYFRAMES
        } else {
            0
        }
}
