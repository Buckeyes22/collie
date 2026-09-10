package com.lateapex.collie

import android.app.Application
import com.lateapex.collie.ui.NativePreferences

class CollieApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        NativePreferences(this).applyTheme()
        container = AppContainer(this)
    }
}
