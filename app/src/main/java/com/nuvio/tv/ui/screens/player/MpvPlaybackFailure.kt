package com.nuvio.tv.ui.screens.player

import `is`.xyz.mpv.MPVNode

/** Only explicit libmpv errors trigger fallback; EOF, stop and playlist redirects do not. */
internal fun mpvPlaybackFailure(event: MPVNode): String? {
    if (event["reason"]?.asString() != "error") return null
    return event["error"]?.asString()?.takeIf { it.isNotBlank() } ?: "libmpv playback failed"
}
