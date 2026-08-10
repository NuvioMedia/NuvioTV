package com.nuvio.tv.domain.model

private const val DEFAULT_SKIP_STEP = 100

/**
 * Whether this catalog supports a given extra argument (e.g. "skip", "search", "genre").
 *
 * Stremio manifests declare extras in either:
 * - full form: `extra: [{ "name": "skip" }, ...]`
 * - short form: `extraSupported: ["skip", ...]` / `extraRequired: [...]`
 *
 * Both must be checked; otherwise short-form catalogs never paginate (hasMore stays false
 * after the first batch).
 */
fun CatalogDescriptor.supportsExtra(name: String): Boolean {
    if (extra.any { it.name.equals(name, ignoreCase = true) }) return true
    if (extraSupported.any { it.equals(name, ignoreCase = true) }) return true
    if (extraRequired.any { it.equals(name, ignoreCase = true) }) return true
    return false
}

fun CatalogDescriptor.skipStep(defaultStep: Int = DEFAULT_SKIP_STEP): Int {
    if (pageSize != null && pageSize > 0) return pageSize
    // Prefer step inferred from skip extra options when present (e.g. ["0","50","100"]).
    val skipExtra = extra.firstOrNull { it.name.equals("skip", ignoreCase = true) }
    val numericOptions = skipExtra?.options
        .orEmpty()
        .mapNotNull { it.trim().toIntOrNull() }
        .filter { it >= 0 }
        .distinct()
        .sorted()
    if (numericOptions.size >= 2) {
        val step = numericOptions
            .zipWithNext()
            .mapNotNull { (a, b) -> (b - a).takeIf { it > 0 } }
            .minOrNull()
        if (step != null && step > 0) return step
    }
    return defaultStep
}
