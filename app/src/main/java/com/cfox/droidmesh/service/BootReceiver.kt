package com.cfox.droidmesh.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cfox.droidmesh.utils.Logger

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Logger.i("BootReceiver received action: $action")

        if (Intent.ACTION_BOOT_COMPLETED == action || "android.intent.action.QUICKBOOT_POWERON" == action) {
            Logger.i("Auto-starting UpdaterForegroundService on boot")
            UpdaterForegroundService.startService(context)
        }
    }
}
