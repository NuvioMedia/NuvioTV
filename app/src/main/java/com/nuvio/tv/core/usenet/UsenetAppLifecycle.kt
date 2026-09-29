package com.nuvio.tv.core.usenet

import android.app.Activity
import android.app.Application
import android.os.Bundle

/** Default launch does not construct the engine/client. Explicit launch warmup
 * remains opt-in; the source screen can warm after Usenet results arrive. */
class UsenetAppLifecycle : Application.ActivityLifecycleCallbacks {
    private var started = 0
    override fun onActivityStarted(activity: Activity) {
        started++
        UsenetSidecar.onAppForegrounded()
        if (started == 1 && activity.getSharedPreferences("usenet_performance", android.content.Context.MODE_PRIVATE)
                .getBoolean("prewarmOnLaunch", false)) UsenetSidecar.get(activity).prewarm()
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
