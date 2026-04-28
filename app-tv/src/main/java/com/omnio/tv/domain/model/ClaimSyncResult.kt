package com.omnio.tv.domain.model

data class ClaimSyncResult(
    val ownerId: String? = null,
    val success: Boolean,
    val message: String
)
