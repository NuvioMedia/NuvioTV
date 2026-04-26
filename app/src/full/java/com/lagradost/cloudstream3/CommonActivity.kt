@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity

/** Stub for CommonActivity referenced by some extensions. */
object CommonActivity {
    var activity: Activity? = null

    fun showToast(message: String, duration: Int = 0) {
        // No-op stub — extensions may call this for user notifications
    }
}
