package com.omnio.tv.domain.model

enum class TraktSharingMode {
    OWN,
    SHARED_RW,
    SHARED_READ_ONLY;

    val sharesPrimaryToken: Boolean get() = this == SHARED_RW || this == SHARED_READ_ONLY
    val allowsScrobbleWrite: Boolean get() = this == OWN || this == SHARED_RW

    companion object {
        fun fromStorageString(raw: String?): TraktSharingMode = when (raw?.uppercase()) {
            "SHARED_RW" -> SHARED_RW
            "SHARED_READ_ONLY" -> SHARED_READ_ONLY
            else -> OWN
        }
    }
}
