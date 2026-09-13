package com.nuvio.tv.core.usenet

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Warm only the local runtime while visible; provider connections require Play. */
class UsenetAppLifecycle : Application.ActivityLifecycleCallbacks {
    private var started = 0
    override fun onActivityStarted(activity: Activity) {
        started++
        UsenetSidecar.onAppForegrounded()
        if (started == 1) UsenetSidecar.get(activity).prewarm()
    }
    override fun onActivityStopped(activity: Activity) {
        started = (started - 1).coerceAtLeast(0)
        if (started == 0 && !activity.isChangingConfigurations) UsenetSidecar.stopIdleOnBackground()
    }
    override fun onActivityCreated(activity: Activity, state: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
