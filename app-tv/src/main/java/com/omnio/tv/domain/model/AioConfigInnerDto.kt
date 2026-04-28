package com.omnio.tv.domain.model

data class AioConfigInnerDto(
    val providers: Map<String, Any?> = emptyMap(),
    val apiKeys: Map<String, String> = emptyMap(),
    val catalogs: List<Map<String, Any?>> = emptyList(),
    val settings: Map<String, Any?> = emptyMap(),
)
