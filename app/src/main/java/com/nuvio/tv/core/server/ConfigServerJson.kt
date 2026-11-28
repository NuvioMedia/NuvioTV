package com.nuvio.tv.core.server

/**
 * Reads a JSON array of strings, dropping every other entry.
 *
 * `as? List<String>` erases at runtime, so `{"urls": [1, 2]}` passes the check and throws a
 * `ClassCastException` later; filtering here keeps both config servers on the same rule.
 */
internal fun parseConfigStringList(rawValue: Any?): List<String> {
    val values = rawValue as? List<*> ?: return emptyList()
    return values.asSequence()
        .mapNotNull { (it as? String)?.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .toList()
}
