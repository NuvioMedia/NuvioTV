package com.omnio.tv.domain.model

import com.omnio.tv.domain.util.languageCodeToName

data class Subtitle(
    val id: String,
    val url: String,
    val lang: String,
    val addonName: String,
    val addonLogo: String?
) {
    fun getDisplayLanguage(): String = languageCodeToName(lang)

    companion object {
        fun languageCodeToName(code: String): String = com.omnio.tv.domain.util.languageCodeToName(code)
    }
}
