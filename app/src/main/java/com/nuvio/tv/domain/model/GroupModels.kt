package com.nuvio.tv.domain.model

import androidx.annotation.Keep
import java.util.UUID

@Keep
data class CatalogGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val logoUrl: String? = null,
    val catalogKeys: List<String> = emptyList()
)

@Keep
data class MainGroup(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val posterType: String = "Square",
    val posterSize: String = "Default",
    val logoUrl: String? = null,
    val subGroupIds: List<String> = emptyList()
)
