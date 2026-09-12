package com.cfox.droidmesh

import android.app.Application
import com.cfox.droidmesh.installer.AdbAuthKeys
import com.cfox.droidmesh.service.UpdaterForegroundService
import com.cfox.droidmesh.utils.Logger

class DroidMeshApp : Application() {
    override fun onCreate() {
        super.onCreate()
        System.setProperty("java.net.preferIPv4Stack", "true")
        System.setProperty("java.net.preferIPv6Addresses", "false")
        // INST-BEHAVE-018: give the loopback ADB client its key directory before anything can
        // open a session. noBackupFilesDir so a device-to-device restore never carries a key the
        // target device's adbd has not authorized.
        AdbAuthKeys.init(java.io.File(noBackupFilesDir, "adb"))
        Logger.i("DroidMesh Application onCreate")
        UpdaterForegroundService.startService(this)
    }
}

