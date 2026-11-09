@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity

/** Stub for CommonActivity referenced by some extensions. */
object CommonActivity {
    @JvmField
    var activity: Activity? = null

    fun getActivity(): Activity? = activity

    fun showToast(message: String, duration: Int? = null) {}

    fun showToast(activity: Activity?, message: String, duration: Int? = null) {}
}
