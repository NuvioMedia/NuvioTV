package com.omnio.tv.domain.model

import androidx.compose.runtime.Immutable
import com.omnio.tv.ui.util.languageCodeToName

@Immutable
data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.omnio.tv.ui.util.languageCodeToName(code)
    }
}
