package com.livetranslate

import android.app.Application
import com.livetranslate.a11y.A11yEnabler
import com.livetranslate.a11y.ServiceLog
import com.livetranslate.data.SettingsStore
import com.livetranslate.keepalive.WatchdogJob
import com.livetranslate.translate.DoubaoSession

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ServiceLog.init(this)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { ServiceLog.recordCrash(thread, e) }
            previous?.uncaughtException(thread, e)
        }
        settings = SettingsStore(this)
        DoubaoSession.init(this)
        WatchdogJob.schedule(this)
        A11yEnabler.ensureEnabled(this, source = "启动")
    }

    companion object {
        lateinit var settings: SettingsStore
            private set
    }
}
