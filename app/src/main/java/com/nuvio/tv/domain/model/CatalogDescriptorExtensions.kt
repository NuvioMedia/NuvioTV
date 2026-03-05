package com.nuvio.tv.domain.model

private const val DEFAULT_SKIP_STEP = 100

fun CatalogDescriptor.supportsExtra(name: String): Boolean {
    return extra.any { it.name.equals(name, ignoreCase = true) }
}

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    val skipExtra = extra.firstOrNull { it.name.equals("skip", ignoreCase = true) } ?: return defaultStep
    val numericOptions = skipExtra.options
        .orEmpty()
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .distinct()
        .sorted()

    if (numericOptions.isEmpty()) return defaultStep
    if (numericOptions.size == 1) return numericOptions.first().takeIf { it > 0 } ?: defaultStep

    val step = numericOptions
        .zipWithNext()
        .mapNotNull { (a, b) -> (b - a).takeIf { it > 0 } }
        .minOrNull()

    return step ?: defaultStep
}
