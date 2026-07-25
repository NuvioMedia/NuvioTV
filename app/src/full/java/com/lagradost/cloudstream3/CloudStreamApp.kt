@file:Suppress("unused")

package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import java.lang.ref.WeakReference

/**
 * Stub for CloudStream's CloudStreamApp class.
 *
 * In the real CloudStream app this is the Application class. Extensions
 * reference its companion fields (context, getKey/setKey helpers, etc.).
 * Some plugins (e.g. AniDb) also reference cfUserAgent. Without this stub
 * the class fails to resolve at runtime, crashing the app with
 * NoClassDefFoundError inside OkHttp interceptors running on async threads.
 */
open class CloudStreamApp {
    companion object {
        /** Cloudflare bypass user-agent used by some extensions. */
        @JvmStatic
        var cfUserAgent: String = try {
            USER_AGENT
        } catch (_: Throwable) {
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
        }

        private var _context: WeakReference<Context>? = null

        @JvmStatic
        var context: Context?
            get() = _context?.get()
            set(value) {
                _context = if (value != null) WeakReference(value) else null
            }

        /** Retrieve Activity from Context (matches upstream tailrec helper). */
        @JvmStatic
        fun Context.getActivity(): Activity? {
            var ctx: Context? = this
            while (ctx != null) {
                if (ctx is Activity) return ctx
                ctx = (ctx as? ContextWrapper)?.baseContext
            }
            return null
        }

        // --- DataStore stubs (no-op, matching real CloudStreamApp API) ---

        @JvmStatic
        fun removeKeys(folder: String): Int? = null

        @JvmStatic
        fun <T> setKey(path: String, value: T) {}

        @JvmStatic
        fun <T> setKey(folder: String, path: String, value: T) {}

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String, defVal: T?): T? = defVal

        @JvmStatic
        inline fun <reified T : Any> getKey(path: String): T? = null

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String): T? = null

        @JvmStatic
        inline fun <reified T : Any> getKey(folder: String, path: String, defVal: T?): T? = defVal

        @JvmStatic
        fun getKeys(folder: String): List<String>? = null

        @JvmStatic
        fun removeKey(folder: String, path: String) {}

        @JvmStatic
        fun removeKey(path: String) {}

        @JvmStatic
        fun openBrowser(url: String, fallbackWebView: Boolean = false) {}
    }
}
