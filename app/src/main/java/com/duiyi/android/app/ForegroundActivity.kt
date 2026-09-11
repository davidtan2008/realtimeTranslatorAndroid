package com.duiyi.android.app

import android.app.Activity
import android.app.Application
import android.content.Context
import android.os.Bundle

object ForegroundActivity : Application.ActivityLifecycleCallbacks {
    @Volatile
    var current: Activity? = null
        private set

    fun hostContext(fallback: Context): Context = current ?: fallback

    override fun onActivityResumed(activity: Activity) {
        current = activity
    }

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (current === activity) current = null
    }
}
