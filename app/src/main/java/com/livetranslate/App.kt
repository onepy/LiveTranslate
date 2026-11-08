package com.livetranslate

import android.app.Application
import com.livetranslate.a11y.A11yEnabler
import com.livetranslate.data.SettingsStore
import com.livetranslate.keepalive.WatchdogJob
import com.livetranslate.translate.DoubaoSession

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        settings = SettingsStore(this)
        DoubaoSession.init(this)
        WatchdogJob.schedule(this)
        A11yEnabler.ensureEnabled(this)
    }

    companion object {
        lateinit var settings: SettingsStore
            private set
    }
}
