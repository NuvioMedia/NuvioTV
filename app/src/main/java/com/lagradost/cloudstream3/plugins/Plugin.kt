package com.lagradost.cloudstream3.plugins

import android.app.Activity
import android.content.Context
import android.util.Log
import com.lagradost.cloudstream3.MainAPI
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.extractorApis

/**
 * The base class that CloudStream extensions extend in NuvioTV.
 * Kept standalone (not extending BasePlugin) because BasePlugin's registration
 * methods are final and can't be overridden. Extensions compiled against the
 * real CloudStream app reference this class directly.
 */
open class Plugin {
    private val _registeredMainAPIs = mutableListOf<MainAPI>()
    private val _registeredExtractorAPIs = mutableListOf<ExtractorApi>()

    val registeredMainAPIs: List<MainAPI> get() = _registeredMainAPIs
    val registeredExtractorAPIs: List<ExtractorApi> get() = _registeredExtractorAPIs

    /** Extensions can set this to provide a settings UI callback. No-op in NuvioTV. */
    var openSettings: ((Context) -> Unit)? = null

    /** Full file path to the plugin (matches BasePlugin's property). */
    var filename: String? = null

    /**
     * No-arg load matching BasePlugin's pattern. Extensions compiled against
     * CloudStream may override this instead of load(Activity?).
     */
    open fun load() {}

    /**
     * Called when the plugin is loaded. Override to register APIs.
     * The [activity] parameter may be null when loaded outside an Activity context.
     * Delegates to no-arg load() so BasePlugin-style extensions get invoked.
     */
    @Suppress("UNUSED_PARAMETER")
    open fun load(activity: Activity?) {
        load()
    }

    fun registerMainAPI(element: MainAPI) {
        Log.d("CS3Plugin", "registerMainAPI called: ${element.name} (${element.javaClass.name})")
        _registeredMainAPIs.add(element)
        // Also register globally for extensions that access APIHolder directly
        element.sourcePlugin = this.filename
        try {
            com.lagradost.cloudstream3.APIHolder.addPluginMapping(element)
        } catch (_: Exception) {}
    }

    fun registerExtractorAPI(element: ExtractorApi) {
        Log.d("CS3Plugin", "registerExtractorAPI called: ${element.name} (${element.javaClass.name})")
        _registeredExtractorAPIs.add(element)
        element.sourcePlugin = this.filename
        extractorApis.add(element)
    }

    // Some extensions call these overloads
    open fun load(context: Context) {
        load(context as? Activity)
    }
}
