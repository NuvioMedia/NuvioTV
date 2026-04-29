package com.omnio.phone.ui.screens.player

import android.app.Activity

/**
 * Process-scoped bridge between the active player Composable and `MainActivity`'s
 * `onUserLeaveHint()`. The Compose layer registers a callback while it owns the
 * player surface; the activity invokes it when the user leaves the app
 * (Home / recents) so we can transition into PiP without re-creating the activity.
 */
object PhonePlayerPipController {

    @Volatile
    private var enterPipHandler: ((Activity) -> Unit)? = null

    fun register(handler: (Activity) -> Unit) {
        enterPipHandler = handler
    }

    fun unregister(handler: (Activity) -> Unit) {
        if (enterPipHandler === handler) enterPipHandler = null
    }

    fun onUserLeaveHint(activity: Activity) {
        enterPipHandler?.invoke(activity)
    }
}
