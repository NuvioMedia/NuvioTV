package com.nuvio.tv.core.profile

/** What Remember Last Profile does with the last used profile when the app starts. */
internal enum class LastProfileStart {
    /** Remember Last Profile is off, or no profile was picked yet: the picker rules apply. */
    NotRemembered,

    /** Opens the profile without the picker. */
    Open,

    /** Opens the profile's PIN first. */
    AskPin,

    /** Still reading whether the profile has a PIN or asks for it on app start. */
    Undecided
}

/**
 * A profile with a PIN is opened without it only when the profile turned "Always ask PIN on app start"
 * off. [hasPin] and [asksPinOnAppStart] are null until read.
 */
internal fun lastProfileStart(
    rememberLastProfile: Boolean,
    hasEverSelectedProfile: Boolean,
    hasPin: Boolean?,
    asksPinOnAppStart: Boolean?
): LastProfileStart = when {
    !rememberLastProfile || !hasEverSelectedProfile -> LastProfileStart.NotRemembered
    hasPin == null -> LastProfileStart.Undecided
    !hasPin -> LastProfileStart.Open
    asksPinOnAppStart == null -> LastProfileStart.Undecided
    asksPinOnAppStart -> LastProfileStart.AskPin
    else -> LastProfileStart.Open
}
