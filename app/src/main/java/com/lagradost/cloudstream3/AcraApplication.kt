@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import com.lagradost.api.setContext
import java.lang.ref.WeakReference

/**
 * Stub for CloudStream's AcraApplication.
 * Extensions (e.g. Yflix) reference AcraApplication.context and the getKey/setKey helpers.
 * We provide no-op implementations since we don't need extension settings persistence.
 */
open class AcraApplication {
    companion object {
        /** Application context stub. Extensions use this for PackageManager etc. */
        @JvmStatic
        var context: Context? = null
            set(value) {
                field = value
                // Also set the library's context so WebViewResolver and other
                // library components can access it
                if (value != null) {
                    setContext(WeakReference(value))
                }
            }

        /**
         * Weak reference to the current Activity. CloudStream extensions
         * often require a non-null Activity in their load() method to
         * register MainAPIs. Set from MainActivity.onCreate().
         */
        private var activityRef: WeakReference<Activity>? = null

        @JvmStatic
        fun getActivity(): Activity? = activityRef?.get()

        @JvmStatic
        fun setActivity(activity: Activity?) {
            activityRef = if (activity != null) WeakReference(activity) else null
            // Update the library's context to the Activity (preferred for WebView)
            if (activity != null) {
                setContext(WeakReference(activity))
            }
        }

        /** Retrieve a stored value. No-op — always returns the default. */
        @JvmStatic
        fun <T> getKey(path: String, key: String, default: T? = null): T? = default

        /** Retrieve a stored value (single-key overload). */
        @JvmStatic
        fun <T> getKey(key: String, default: T? = null): T? = default

        /** Store a value. No-op. */
        @JvmStatic
        fun setKey(path: String, key: String, value: Any?) {}

        /** Store a value (single-key overload). No-op. */
        @JvmStatic
        fun setKey(key: String, value: Any?) {}

        /** Remove stored keys by prefix. No-op. */
        @JvmStatic
        fun removeKeys(prefix: String) {}

        /** Remove a single stored key. No-op. */
        @JvmStatic
        fun removeKey(path: String, key: String) {}

        /** Remove a single stored key (single-key overload). No-op. */
        @JvmStatic
        fun removeKey(key: String) {}
    }
}
