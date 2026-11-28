package com.nuvio.tv.ui.util

import com.nuvio.tv.core.util.formatRuntimeMinutes
import com.nuvio.tv.core.util.parseRuntimeMinutes

fun formatHeroRuntime(runtime: String?): String? {
    val normalized = runtime?.trim()?.lowercase()?.takeIf { it.isNotBlank() } ?: return null
    val totalMinutes = parseRuntimeMinutes(normalized) ?: return runtime
    // TMDB answers with runtime 0, not null, for a title whose length it does not know yet --
    // common for anything newly added. Formatting that produced a literal "0m" next to the
    // genre. No runtime is the honest reading of zero, and callers already drop a null.
    if (totalMinutes <= 0) return null

    return formatRuntimeMinutes(totalMinutes)
}
