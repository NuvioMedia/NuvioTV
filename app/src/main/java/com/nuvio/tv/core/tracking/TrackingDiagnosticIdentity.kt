package com.nuvio.tv.core.tracking

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

internal object TrackingDiagnosticIdentity {
    private val aliases = ConcurrentHashMap<String, String>()
    private val nextAlias = AtomicInteger()

    fun alias(contentId: String): String {
        val normalized = contentId.trim().lowercase(Locale.ROOT)
        aliases[normalized]?.let { return it }
        val candidate = "cw-${nextAlias.incrementAndGet()}"
        return aliases.putIfAbsent(normalized, candidate) ?: candidate
    }
}
