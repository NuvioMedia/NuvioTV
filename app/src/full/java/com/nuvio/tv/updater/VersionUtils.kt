package com.nuvio.tv.updater

internal object VersionUtils {

    fun normalize(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        return raw.trim().removePrefix("v").removePrefix("V")
    }

    fun parseVersionParts(raw: String?): List<Int>? {
        val normalized = normalize(raw)
        if (normalized.isBlank()) return null

        val parts = normalized.split('.', '-', '_')
            .filter { it.isNotBlank() }
            .mapNotNull { token -> token.takeWhile { it.isDigit() }.toIntOrNull() }

        return parts.takeIf { it.isNotEmpty() }
    }

    fun isRemoteNewer(remote: String?, local: String?): Boolean {
        val remoteParts = parseVersionParts(remote)
        val localParts = parseVersionParts(local)

        if (remoteParts == null || localParts == null) {
            val r = normalize(remote)
            val l = normalize(local)
            return r.isNotBlank() && l.isNotBlank() && r != l
        }

        val max = maxOf(remoteParts.size, localParts.size)
        for (i in 0 until max) {
            val r = remoteParts.getOrElse(i) { 0 }
            val l = localParts.getOrElse(i) { 0 }
            if (r != l) return r > l
        }
        return false
    }
}
