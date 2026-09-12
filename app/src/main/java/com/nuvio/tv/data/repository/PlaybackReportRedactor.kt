package com.nuvio.tv.data.repository

import java.net.URLDecoder
import java.util.Locale

/** Redaction for outbound playback reports, including untrusted exception and raw event text.
 * This is defense in depth, not an anonymizer: unknown secrets in free text cannot be identified.
 */
internal object PlaybackReportRedactor {
    private const val REDACTED = "[redacted]"
    private val encodedByte = Regex("(?:%[0-9a-fA-F]{2})+")
    private val unicodeEscape = Regex("""\\u([0-9a-fA-F]{4})""")
    private val url = Regex("""(?i)(?:[a-z][a-z0-9+.-]*://|//(?=[a-z0-9])|magnet:|www\.)[^\s<>"']+""")
    private val header = Regex("""(?i)\b(?:authorization|proxy-authorization|cookie|set-cookie)["']?\s*[:=][^\r\n]*""")
    private val credential = Regex(
        """(?i)\b[\w-]*(?:token|password|passwd|secret|credential|api[_-]?key|authorization|cookie|signature|username|auth|pwd|sig)[\w-]*["']?\s*[:=]\s*(?:"[^"]*"|'[^']*'|[^\s,;&}\]]+)"""
    )
    private val bearer = Regex("""(?i)\b(?:bearer|basic)\s+[a-z0-9+/_=.-]+""")
    private val sensitiveKey = Regex("token|password|passwd|secret|credential|apikey|authorization|cookie|signature|username|auth|pwd|sig")
    private val headerName = Regex("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}")

    fun text(value: String, maxLength: Int): String {
        // Decode before matching and truncate afterwards so an encoded or long URL cannot
        // leave a credential fragment behind. Excessive encoding fails closed for this field.
        var decoded = value
        repeat(4) {
            decoded = unicodeEscape.replace(decoded) { it.groupValues[1].toInt(16).toChar().toString() }
                .replace("\\/", "/")
            decoded = encodedByte.replace(decoded) { URLDecoder.decode(it.value, "UTF-8") }
        }
        if (encodedByte.containsMatchIn(decoded) || unicodeEscape.containsMatchIn(decoded)) {
            return REDACTED.take(maxLength)
        }
        return decoded
            .replace(header, REDACTED)
            .replace(url, "[redacted-url]")
            .replace(credential, REDACTED)
            .replace(bearer, REDACTED)
            .replace(Regex("[\\p{Cntrl}\\s]+"), " ")
            .trim()
            .take(maxLength)
    }

    fun detailValue(key: String, value: String, maxLength: Int): String {
        val decodedKey = text(key, key.length)
        // The key itself is untrusted too: an encoded key must not bypass classification.
        if (decodedKey.contains("[redacted")) return REDACTED
        val normalizedKey = decodedKey.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }
        return if (sensitiveKey.containsMatchIn(normalizedKey)) REDACTED else text(value, maxLength)
    }

    fun headerNames(names: Collection<String>): List<String> = names
        .filter { headerName.matches(it) }
        .map { it.lowercase(Locale.ROOT) }
        .distinct()
        .sorted()
        .take(40)
}
