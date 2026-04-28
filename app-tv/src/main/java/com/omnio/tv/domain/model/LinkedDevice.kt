package com.omnio.tv.domain.model

data class LinkedDevice(
    val id: String? = null,
    val ownerId: String,
    val deviceUserId: String,
    val deviceName: String? = null,
    val linkedAt: String? = null
)
