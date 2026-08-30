package com.cfox.kiosksatelliteupdater

import android.app.Application
import com.cfox.kiosksatelliteupdater.service.UpdaterForegroundService
import com.cfox.kiosksatelliteupdater.utils.Logger

class KioskSatelliteUpdaterApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.i("Kiosk Satellite Updater Application onCreate")
        UpdaterForegroundService.startService(this)
    }
}
