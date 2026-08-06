package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** VC-1 / WMV detection shared by track selection and first-frame recovery. */
internal object Vc1VideoFormatHeuristics {

    fun isLikelyVc1(
        sampleMimeType: String?,
        codecs: String?,
        label: String?,
    ): Boolean {
        if (sampleMimeType?.equals(MimeTypes.VIDEO_VC1, ignoreCase = true) == true) {
            return true
        }

        val haystack = listOfNotNull(codecs, label)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return haystack.contains("wvc1") ||
            haystack.contains("vc-1") ||
            haystack.contains("wmv3") ||
            Regex("(?<![a-z0-9])vc1(?![a-z0-9])").containsMatchIn(haystack)
    }
}
