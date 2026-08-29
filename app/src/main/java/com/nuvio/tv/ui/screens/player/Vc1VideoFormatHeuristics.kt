package com.nuvio.tv.ui.screens.player

import androidx.media3.common.MimeTypes
import java.util.Locale

/** VC-1 / WMV detection shared by track selection, codec selection and first-frame recovery. */
internal object Vc1VideoFormatHeuristics {

    fun isLikelyVc1(
        sampleMimeType: String?,
        codecs: String?,
        label: String?,
    ): Boolean {
        if (isVc1OrWmvMime(sampleMimeType)) {
            return true
        }

        val haystack = listOfNotNull(codecs, label)
            .joinToString(" ")
            .lowercase(Locale.ROOT)

        return haystack.contains("wvc1") ||
            haystack.contains("vc-1") ||
            haystack.contains("wmv3") ||
            haystack.contains("wmv1") ||
            haystack.contains("wmv2") ||
            Regex("(?<![a-z0-9])vc1(?![a-z0-9])").containsMatchIn(haystack)
    }

    fun isVc1OrWmvMime(sampleMimeType: String?): Boolean {
        if (sampleMimeType.isNullOrEmpty()) return false
        val mime = sampleMimeType.lowercase(Locale.ROOT)
        return mime == MimeTypes.VIDEO_VC1 ||
            mime == "video/wvc1" ||
            mime == "video/vc1" ||
            mime == "video/x-ms-wmv" ||
            mime == "video/wmv" ||
            mime == "video/x-ms-wmv3" ||
            mime == "video/x-ms-wmv1" ||
            mime == "video/x-ms-wmv2"
    }
}
