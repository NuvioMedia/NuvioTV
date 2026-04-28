package com.nuvio.tv.core.runtime

import android.app.Activity
import android.app.Application

object PluginRuntimeHooks {
    fun onApplicationCreate(application: Application) = Unit

    fun onActivityCreate(activity: Activity) = Unit

    fun onActivityDestroy() = Unit
}
