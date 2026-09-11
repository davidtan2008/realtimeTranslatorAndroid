package com.duiyi.android.app

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class DuiyiApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        com.duiyi.android.services.DuiyiLog.install(this)
        registerActivityLifecycleCallbacks(ForegroundActivity)
    }
}
