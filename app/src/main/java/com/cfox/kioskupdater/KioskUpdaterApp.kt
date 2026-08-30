package com.cfox.kioskupdater

import android.app.Application
import com.cfox.kioskupdater.service.UpdaterForegroundService
import com.cfox.kioskupdater.utils.Logger

class KioskUpdaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.i("KioskUpdater Application onCreate")
        UpdaterForegroundService.startService(this)
    }
}
