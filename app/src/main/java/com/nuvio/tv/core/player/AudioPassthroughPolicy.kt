/*
 * The per-format passthrough model (one switch per compressed format, phrased as a
 * receiver capability) follows Kodi's audiooutput.{ac3,eac3,dts,truehd,dtshd}passthrough
 * settings. Kodi is GPL-2.0-or-later. No Kodi code is reproduced in this file; the
 * user-facing label wording, which is partly verbatim, is credited where it lives in
 * res/values/strings.xml.
 */
package com.nuvio.tv.core.player

import androidx.media3.common.MimeTypes

data class AudioPassthroughPolicy(
    val allowAc3: Boolean = true,
    val allowEac3: Boolean = true,
    val allowTrueHd: Boolean = true,
    val allowDts: Boolean = true,
    val allowDtsHd: Boolean = true,
    val softwareDecodersAvailable: Boolean = true,
    val learnedDeniedGroups: Set<Group> = emptySet()
) {

    enum class Group { AC3, EAC3, TRUEHD, DTS, DTS_HD }

    fun deniesPassthrough(mimeType: String?): Boolean {
        if (!softwareDecodersAvailable) return false
        val group = groupOf(mimeType) ?: return false
        if (group in learnedDeniedGroups) return true
        return when (group) {
            Group.AC3 -> !allowAc3
            Group.EAC3 -> !allowEac3
            Group.TRUEHD -> !allowTrueHd
            Group.DTS -> !allowDts
            Group.DTS_HD -> !allowDtsHd
        }
    }

    fun allowsEverything(): Boolean =
        !softwareDecodersAvailable ||
            (allowAc3 && allowEac3 && allowTrueHd && allowDts && allowDtsHd && learnedDeniedGroups.isEmpty())

    companion object {
        val ALLOW_ALL = AudioPassthroughPolicy()

        fun groupOf(mimeType: String?): Group? = when (mimeType) {
            MimeTypes.AUDIO_AC3 -> Group.AC3
            MimeTypes.AUDIO_E_AC3, MimeTypes.AUDIO_E_AC3_JOC -> Group.EAC3
            MimeTypes.AUDIO_TRUEHD -> Group.TRUEHD
            MimeTypes.AUDIO_DTS -> Group.DTS
            MimeTypes.AUDIO_DTS_HD -> Group.DTS_HD
            else -> null
        }
    }
}
