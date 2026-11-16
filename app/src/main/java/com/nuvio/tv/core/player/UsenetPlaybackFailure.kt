package com.nuvio.tv.core.player

import androidx.annotation.StringRes
import com.nuvio.tv.R

/** The failure kind the Usenet engine sent with a stream response (X-Usenet-Failure), if any. */
internal fun usenetFailureKind(responseHeaders: Map<String, List<String>>): String? =
    responseHeaders.entries.firstOrNull { it.key.equals("X-Usenet-Failure", ignoreCase = true) }
        ?.value?.firstOrNull { it.isNotBlank() }?.trim()

/**
 * What the error screen says for an engine failure kind. The engine answers a stream it gave up on
 * with 410 Gone, which would otherwise read as an expired link.
 */
@StringRes
internal fun usenetFailureExplanation(kind: String?): Int? = when (kind) {
    "missing-article", "hole-limit" -> R.string.player_error_usenet_missing_parts
    "invalid-article" -> R.string.player_error_usenet_damaged_parts
    "provider-authentication" -> R.string.player_error_usenet_provider_login
    "provider-quota" -> R.string.player_error_usenet_provider_quota
    "blank-content" -> R.string.player_error_usenet_empty_release
    else -> null
}

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
