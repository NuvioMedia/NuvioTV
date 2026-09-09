package com.nuvio.tv.core.network

/**
 * Redacts likely-sensitive values (API keys, tokens, session ids, passwords) out of text
 * before it's written to logcat. Used for diagnostic logging of URLs/bodies that come from
 * or are constructed by third-party scraper/plugin code, which commonly embeds a debrid
 * provider's API key as a query param or JSON field.
 *
 * Deliberately a plain string-replace, not full URL/JSON parsing: it must never throw on
 * malformed input, since it only feeds best-effort debug logging.
 */
object LogSanitizer {
    private val SENSITIVE_KEY_NAMES =
        "api[_-]?key|access[_-]?token|refresh[_-]?token|auth(?:orization)?|token|secret|password|passwd|session(?:id)?|client[_-]?secret"

    // key=value in a query string, e.g. "...?apikey=abcd1234&other=1"
    private val QUERY_PARAM_REGEX =
        Regex("(?i)\\b($SENSITIVE_KEY_NAMES)=([^&\\s\"']+)")

    // "key": "value" or "key":"value" in a JSON body
    private val JSON_FIELD_REGEX =
        Regex("(?i)\"($SENSITIVE_KEY_NAMES)\"\\s*:\\s*\"([^\"]*)\"")

    // Bearer/Basic/Token <credential> in an Authorization-style header value
    private val AUTH_SCHEME_REGEX =
        Regex("(?i)\\b(Bearer|Basic|Token)\\s+[^\\s\"']+")

    fun redact(text: String): String {
        if (text.isEmpty()) return text
        return AUTH_SCHEME_REGEX.replace(
            JSON_FIELD_REGEX.replace(
                QUERY_PARAM_REGEX.replace(text) { "${it.groupValues[1]}=REDACTED" }
            ) { "\"${it.groupValues[1]}\":\"REDACTED\"" }
        ) { "${it.groupValues[1]} REDACTED" }
    }
}
