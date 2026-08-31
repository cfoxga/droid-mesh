package com.cfox.droidmesh

import android.app.Application
import com.cfox.droidmesh.service.UpdaterForegroundService
import com.cfox.droidmesh.utils.Logger

class DroidMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Logger.i("Kiosk Satellite Updater Application onCreate")
        UpdaterForegroundService.startService(this)
    }
}
