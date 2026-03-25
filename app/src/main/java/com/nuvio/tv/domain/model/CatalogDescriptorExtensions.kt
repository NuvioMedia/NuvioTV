package com.nuvio.tv.domain.model

private const val DEFAULT_SKIP_STEP = 100

fun CatalogDescriptor.supportsExtra(name: String): Boolean {
    return extra.any { it.name.equals(name, ignoreCase = true) }
}

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    if (pageSize != null && pageSize > 0) return pageSize
    return defaultStep
}
