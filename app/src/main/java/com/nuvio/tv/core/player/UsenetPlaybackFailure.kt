package com.nuvio.tv.core.player

/** A native engine verdict cannot be repaired by changing decoders or retrying
 * the same URL. Ordinary HTTP streams and temporary Usenet errors keep their
 * existing recovery policy. */
internal fun isPermanentUsenetHttpFailure(
    nativeUsenet: Boolean,
    responseCode: Int?,
    responseHeaders: Map<String, List<String>>
): Boolean = nativeUsenet && responseCode == 410 && responseHeaders.entries.any { (name, values) ->
    name.equals("X-Usenet-Failure", ignoreCase = true) && values.any {
        it in setOf("missing-article", "hole-limit", "invalid-article", "provider-authentication", "provider-quota")
    }
}
