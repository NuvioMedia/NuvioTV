package com.nuvio.tv.core.usenet

import java.io.IOException

/** Control-plane failures only; media errors still use the player's recovery ladder. */
internal class UsenetPreparationException(
    message: String,
    val scope: Scope = Scope.SOURCE
) : IOException(message) {
    enum class Scope { SOURCE, PROVIDER, ENGINE }
}
